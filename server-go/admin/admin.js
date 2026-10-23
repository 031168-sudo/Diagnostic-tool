'use strict';
const $ = id => document.getElementById(id);
let accounts = [], selected = '', generation = 0;
const number = n => new Intl.NumberFormat('ru-RU', {maximumFractionDigits: 1}).format(n || 0);
const money = n => '$' + ((n || 0) / 1e6).toFixed(6);
const operations = {analysis: 'Анализ', analysis_retry: 'Повтор JSON', chat: 'Чат', finalize: 'Заключение', stt: 'Распознавание речи'};
const empty = () => ({calls:0,inMiss:0,inHit:0,out:0,audioSec:0,costMicros:0});
function sum(rows) {
  const total = empty();
  for (const row of rows) for (const key of Object.keys(total)) total[key] += row[key] || 0;
  return total;
}
function cards(id, total) {
  $(id).replaceChildren();
  for (const [label, value] of [['Расходы, USD', money(total.costMicros)], ['Вызовы', number(total.calls)], ['Вход без кэша', number(total.inMiss)], ['Вход из кэша', number(total.inHit)], ['Выходные токены', number(total.out)], ['Аудио, минут', number(total.audioSec / 60)]]) {
    const box = document.createElement('div'); box.className = 'card';
    const name = document.createElement('span'); name.textContent = label;
    const val = document.createElement('strong'); val.textContent = value;
    box.append(name, val); $(id).append(box);
  }
}
function table(id, title, rows, onSelect, clients) {
  const target = $(id); target.replaceChildren();
  if (!rows.length) { target.textContent = 'Нет данных за выбранный период.'; return; }
  const table = document.createElement('table'), head = document.createElement('thead'), tr = document.createElement('tr');
  for (const text of [title, ...(clients ? ['Регистрация (UTC)', 'Диагностик', 'Подписка до (UTC)'] : []), 'Вызовы', 'Вход без кэша', 'Кэш', 'Выход', 'Аудио, мин', 'USD']) {
    const th = document.createElement('th'); th.textContent = text; th.scope = 'col'; tr.append(th);
  }
  head.append(tr); table.append(head);
  const body = document.createElement('tbody');
  for (const [key, totals] of rows) {
    const tr = document.createElement('tr'), name = document.createElement('td');
    if (onSelect) {
      const button = document.createElement('button'); button.className = 'link'; button.textContent = key;
      button.addEventListener('click', () => onSelect(key)); name.append(button);
    } else name.textContent = key;
    tr.append(name);
    if (clients) {
      const client = clients.get(key);
      for (const text of [client.createdAt ? client.createdAt.slice(0,10) : 'Не зарегистрирован', client.registered ? number(client.credits) : '—', client.subscriptionUntil ? client.subscriptionUntil.slice(0,10) : 'Нет']) {
        const td = document.createElement('td'); td.textContent = text; tr.append(td);
      }
    }
    for (const text of [number(totals.calls), number(totals.inMiss), number(totals.inHit), number(totals.out), number(totals.audioSec / 60), money(totals.costMicros)]) {
      const td = document.createElement('td'); td.textContent = text; tr.append(td);
    }
    body.append(tr);
  }
  table.append(body); target.append(table);
}
function renderAccounts() {
  const query = $('search').value.trim().toLowerCase();
  table('accounts', 'Аккаунт', accounts.filter(a => a.accountId.toLowerCase().includes(query)).map(a => [a.accountId, a.totals]), showAccount, new Map(accounts.map(a => [a.accountId,a])));
}
async function request(accountId, signal) {
  const params = new URLSearchParams({from:$('from').value, to:$('to').value});
  if (accountId) params.set('accountId', accountId);
  const response = await fetch('/v1/admin/usage?' + params, {credentials:'same-origin', cache:'no-store', signal});
  const data = await response.json();
  if (!response.ok) throw new Error(response.status === 403 ? 'Неверный ADMIN_TOKEN или доступ отключён.' : data.error || 'Не удалось загрузить отчёт');
  return data;
}
async function refresh() {
  const version = ++generation;
  $('status').textContent = 'Загрузка…'; $('detail').hidden = true; selected = '';
  // Clear stale data immediately when dates or credentials change.
  accounts = []; renderAccounts(); $('summary').replaceChildren(); $('prices').textContent = '';
  try {
    const data = await request();
    if (version !== generation) return;
    accounts = data.accounts || []; cards('summary', sum(accounts.map(a => a.totals))); renderAccounts();
    const p = data.price;
    $('prices').textContent = `Версия: ${p.version}. За 1 млн токенов: вход $${p.inMiss}, кэш $${p.inHit}, выход $${p.out}. STT: $${p.sttPerMin}/мин.`;
    $('login').hidden = true; $('dashboard').hidden = false; $('logout').hidden = false;
    $('status').textContent = `Обновлено: ${new Date().toLocaleTimeString('ru-RU')}. Зарегистрированных клиентов: ${accounts.filter(a => a.registered).length}. С вызовами за период: ${accounts.filter(a => a.totals.calls > 0).length}.`;
  } catch (error) {
    if (version === generation) {
      $('status').textContent = error.message;
      $('dashboard').hidden = true; $('login').hidden = false; $('logout').hidden = true;
    }
  }
}
async function showAccount(accountId) {
  selected = accountId; const version = generation;
  $('detail').hidden = true; $('status').textContent = 'Загружаю детализацию…';
  try {
    const data = await request(accountId);
    if (version !== generation || selected !== accountId) return;
    $('detail-title').textContent = 'Клиент: ' + accountId;
    cards('detail-summary', data.totals);
    table('days', 'Дата (UTC)', Object.entries(data.byDay).sort((a,b) => a[0].localeCompare(b[0])));
    table('ops', 'Операция', Object.entries(data.byOp).map(([key,value]) => [operations[key] || key,value]));
    table('sessions', 'ID сессии', Object.entries(data.bySession).sort((a,b) => b[1].costMicros-a[1].costMicros));
    $('detail').hidden = false; $('status').textContent = ''; $('detail').scrollIntoView({behavior:'smooth',block:'start'});
  } catch (error) { if (version === generation && selected === accountId) $('status').textContent = error.message; }
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const token = $('token').value.trim(); $('token').value = '';
  try {
    const response = await fetch('/v1/admin/login', {method:'POST', headers:{'X-Admin-Token':token}, credentials:'same-origin'});
    if (!response.ok) throw new Error('Неверный ADMIN_TOKEN или доступ отключён.');
    await refresh();
  } catch (error) { $('status').textContent = error.message; }
});
$('filters').addEventListener('submit', event => { event.preventDefault(); refresh(); });
$('search').addEventListener('input', renderAccounts);
$('logout').addEventListener('click', async () => {
  try {
    const response = await fetch('/v1/admin/logout', {method:'POST', credentials:'same-origin'});
    if (!response.ok) throw new Error('Не удалось завершить сессию');
  } catch (error) { $('status').textContent = error.message; return; }
  generation++; accounts = []; selected = ''; $('search').value = '';
  for (const id of ['accounts','summary','detail-summary','days','ops','sessions']) $(id).replaceChildren();
  $('prices').textContent = ''; $('detail-title').textContent = ''; $('detail').hidden = true;
  $('dashboard').hidden = true; $('logout').hidden = true; $('login').hidden = false; $('status').textContent = ''; $('token').focus();
});
const today = new Date(); $('to').value = today.toISOString().slice(0,10);
today.setUTCDate(today.getUTCDate()-29); $('from').value = today.toISOString().slice(0,10);
refresh();
