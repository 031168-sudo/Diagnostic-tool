import express from "express";
import multer from "multer";
import OpenAI from "openai";
import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";

const app = express();
const port = Number(process.env.PORT || 8080);
const root = path.resolve(process.env.DATA_DIR || "./data");
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
const model = process.env.OPENAI_MODEL || "gpt-5.6-sol";
const transcribeModel = process.env.OPENAI_TRANSCRIBE_MODEL || "gpt-4o-transcribe";
const upload = multer({ dest: path.join(root, "uploads"), limits: { fileSize: 120 * 1024 * 1024, files: 10 } });

await fs.mkdir(path.join(root, "uploads"), { recursive: true });
app.use(express.json({ limit: "2mb" }));
app.get("/health", (_req, res) => res.json({ ok: true, service: "diagnostic-tool" }));

const now = () => new Date().toISOString();
const sessionDir = id => path.join(root, id);
const stateFile = id => path.join(sessionDir(id), "state.json");
async function saveState(s) { await fs.mkdir(sessionDir(s.id), { recursive: true }); await fs.writeFile(stateFile(s.id), JSON.stringify(s, null, 2), "utf8"); }
async function loadState(id) { return JSON.parse(await fs.readFile(stateFile(id), "utf8")); }
function publicState(s) {
  return { id:s.id, state:s.state, stage:s.stage || "", message:s.message || "", question:s.question || "", options:s.options || [], conclusion:s.conclusion || "", pdfUrl:s.conclusion ? `/v1/diagnostics/${s.id}/conclusion.txt` : "" };
}
function parseModelJson(text) {
  const cleaned = text.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "").trim();
  try { return JSON.parse(cleaned); } catch {}
  const a = cleaned.indexOf("{"); const b = cleaned.lastIndexOf("}");
  if (a >= 0 && b > a) return JSON.parse(cleaned.slice(a, b + 1));
  throw new Error("ИИ вернул ответ не в JSON-формате");
}
async function readTextIfExists(dir, filename, max = 3_000_000) {
  try { const b = await fs.readFile(path.join(dir, filename)); return b.toString("utf8", 0, max); } catch { return ""; }
}
function wavMetrics(buffer) {
  if (buffer.length < 44 || buffer.toString("ascii",0,4) !== "RIFF") return { error:"WAV header not recognised" };
  const channels = buffer.readUInt16LE(22), rate = buffer.readUInt32LE(24), bits = buffer.readUInt16LE(34);
  let pos=12, dataStart=-1, dataSize=0;
  while(pos+8<=buffer.length){ const id=buffer.toString("ascii",pos,pos+4); const size=buffer.readUInt32LE(pos+4); if(id==="data"){dataStart=pos+8;dataSize=Math.min(size,buffer.length-dataStart);break;} pos+=8+size+(size&1); }
  if(dataStart<0 || bits!==16) return {channels,rate,bits,error:"Only PCM16 WAV is analysed"};
  const bpf=channels*2, frames=Math.floor(dataSize/bpf), duration=frames/rate, stride=Math.max(1,Math.floor(rate*0.5));
  let rms=0,peak=0,count=0,zc=0,prev=0,first=true;
  for(let f=0;f<frames;f+=stride){const idx=dataStart+f*bpf;if(idx+1>=buffer.length)break;const x=buffer.readInt16LE(idx)/32768;rms+=x*x;peak=Math.max(peak,Math.abs(x));count++;if(!first&&((x>=0)!=(prev>=0)))zc++;first=false;prev=x;}
  return {channels,rate,bits,durationSec:Number(duration.toFixed(2)),sampledPoints:count,rms:Number(Math.sqrt(rms/Math.max(1,count)).toFixed(5)),peak:Number(peak.toFixed(5)),zeroCrossings:zc};
}
async function transcribeAudio(file){
  if(!file)return "";
  try{const r=await openai.audio.transcriptions.create({model:transcribeModel,file:await import("node:fs").then(m=>m.createReadStream(file.path)),language:"ru"});return r.text||"";}catch(e){return `Транскрипция недоступна: ${e.message}`;}
}

const systemPrompt=`Ты — автомобильный диагност приложения Diagnostic Tool. Анализируй только предоставленные данные и явно отделяй факт от гипотезы. Сопоставляй audio, OBD, GPS и датчики по общей временной шкале. Не утверждай неисправность конкретной детали, если данные её не доказывают. Если для различения причин нужен простой дополнительный тест или вопрос владельцу — задай его.

Отвечай СТРОГО одним JSON-объектом без markdown:
{"state":"question|completed","stage":"...","message":"...","question":"...","options":["..."],"conclusion":"..."}
Для question поле conclusion пустое. Для completed question пустое.

При завершении заключение должно быть техническим и структурированным: исходная жалоба; что реально обнаружено в данных; корреляции и временные закономерности; возможные причины с уровнем уверенности; что проверить в первую очередь; контрольные проверки; ограничения анализа.`;

async function callDiagnosticModel(prompt){
  const response=await openai.responses.create({model,store:false,input:[{role:"system",content:systemPrompt},{role:"user",content:prompt}]});
  return parseModelJson(response.output_text||"");
}
async function runAnalysis(s, extra=""){
  s.state="processing";s.stage="Подготовка данных";s.message="Собираю и сопоставляю файлы…";s.updatedAt=now();await saveState(s);
  const dir=sessionDir(s.id),session=await readTextIfExists(dir,"session.json",500_000),obd=await readTextIfExists(dir,"obd.csv",2_500_000),gps=await readTextIfExists(dir,"gps.csv",1_500_000),sensors=await readTextIfExists(dir,"sensors.csv",2_500_000),audioFile=s.files.find(f=>f.name==="audio.wav");
  let audioMetrics={},transcript="";
  if(audioFile)audioMetrics=wavMetrics(await fs.readFile(audioFile.path));
  s.stage="Анализ звука";s.message="Выделяю акустические характеристики…";await saveState(s);
  if(audioFile)transcript=await transcribeAudio(audioFile);
  s.stage="Сопоставление OBD и датчиков";s.message="Сопоставляю обороты, скорость, нагрузку, MAP и движения автомобиля…";await saveState(s);
  const prompt=`Данные автомобиля:\n${s.car}\n\nЖалоба:\n${s.complaint||"не указана"}\n\nСессия:\n${session}\n\nАудио-метрики:\n${JSON.stringify(audioMetrics)}\n\nРаспознанная речь:\n${transcript}\n\nOBD CSV:\n${obd}\n\nGPS CSV:\n${gps}\n\nSENSORS CSV:\n${sensors}\n\nДополнительный ответ владельца:\n${extra||"нет"}\n\nПроведи диагностический анализ и верни JSON по заданному формату.`;
  const result=await callDiagnosticModel(prompt);
  s.state=result.state||"question";s.stage=result.stage||"Анализ";s.message=result.message||"";s.question=result.question||"";s.options=Array.isArray(result.options)?result.options:[];s.conclusion=result.conclusion||"";s.history=[...(s.history||[]),{role:"user",text:extra||"initial"},{role:"assistant",text:JSON.stringify(result)}];s.updatedAt=now();await saveState(s);
}

app.post("/v1/diagnostics",upload.array("files",10),async(req,res)=>{try{
  if(!process.env.OPENAI_API_KEY)return res.status(503).json({error:"OPENAI_API_KEY не настроен на сервере"});
  const id=crypto.randomUUID(),dir=sessionDir(id);await fs.mkdir(dir,{recursive:true});
  const files=(req.files||[]).map(f=>({name:f.originalname,path:f.path,size:f.size}));
  for(const f of files)await fs.rename(f.path,path.join(dir,f.originalname.replace(/[^a-zA-Z0-9._-]/g,"_")));
  const renamed=files.map(f=>({...f,path:path.join(dir,f.originalname.replace(/[^a-zA-Z0-9._-]/g,"_"))}));
  const s={id,state:"processing",stage:"Очередь диагностики",message:"Сессия принята…",question:"",options:[],conclusion:"",car:req.body.car||"{}",complaint:req.body.complaint||"",sessionName:req.body.sessionName||id,files:renamed,history:[],createdAt:now(),updatedAt:now()};
  await saveState(s);res.json({id});runAnalysis(s).catch(async e=>{s.state="error";s.stage="Ошибка";s.message=e.message;await saveState(s);});
}catch(e){res.status(500).json({error:e.message});}});

app.get("/v1/diagnostics/:id",async(req,res)=>{try{res.json(publicState(await loadState(req.params.id)));}catch{res.status(404).json({error:"Диагностика не найдена"});}});
app.post("/v1/diagnostics/:id/messages",async(req,res)=>{try{const s=await loadState(req.params.id);if(s.state!=="question")return res.status(409).json({error:"Сейчас ответ не требуется"});const text=String(req.body?.text||"").trim();if(!text)return res.status(400).json({error:"Пустой ответ"});s.state="processing";s.stage="Уточнение диагноза";s.message="Учитываю ответ и повторно проверяю данные…";await saveState(s);res.json({ok:true});runAnalysis(s,text).catch(async e=>{s.state="error";s.stage="Ошибка";s.message=e.message;await saveState(s);});}catch(e){res.status(404).json({error:e.message});}});
app.post("/v1/diagnostics/:id/chat",async(req,res)=>{try{const s=await loadState(req.params.id);const text=String(req.body?.text||"").trim();if(!text)return res.status(400).json({error:"Пустое сообщение"});const context=(s.history||[]).slice(-12).map(x=>`${x.role}: ${x.text}`).join("\n");const r=await openai.responses.create({model,store:false,input:[{role:"system",content:"Ты продолжаешь диалог по автомобильной диагностике. Отвечай по имеющимся данным, не придумывай измерения и чётко отделяй факт от предположения."},{role:"user",content:`Заключение:\n${s.conclusion}\nИстория:\n${context}\nНовое сообщение владельца:\n${text}`}]});const answer=r.output_text||"";s.history=[...(s.history||[]),{role:"user",text},{role:"assistant",text:answer}];s.updatedAt=now();await saveState(s);res.json({answer});}catch(e){res.status(500).json({error:e.message});}});
app.get("/v1/diagnostics/:id/conclusion.txt",async(req,res)=>{try{const s=await loadState(req.params.id);if(!s.conclusion)return res.status(404).send("Заключение ещё не готово");res.type("text/plain; charset=utf-8").send(s.conclusion);}catch{res.status(404).send("Диагностика не найдена");}});

app.listen(port,()=>console.log(`Diagnostic Tool server listening on ${port}`));
