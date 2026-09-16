package com.example.diagnostictool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewMainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var obdValues: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var currentCarText: TextView
    private lateinit var connectButton: Button
    private lateinit var obd: TargetElm327Ble
    private var recorder: PublicSessionRecorder? = null
    private val carStore by lazy { CarStore(this) }
    private val recordStore by lazy { DiagnosticRecordStore(this) }
    private val requestCode = 10
    private var pendingConnect = false
    private var lastSessionUris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); obdValues = findViewById(R.id.obdValues); recordButton = findViewById(R.id.record)
        recordStatus = findViewById(R.id.recordStatus); currentCarText = findViewById(R.id.currentCar); connectButton = findViewById(R.id.connect)
        findViewById<Button>(R.id.changeCar).setOnClickListener { showCarsDialog() }
        findViewById<Button>(R.id.addCar).setOnClickListener { addCarAndReturnToDiagnostic() }
        findViewById<Button>(R.id.history).setOnClickListener { showHistory() }
        connectButton.setOnClickListener { requestAndConnect() }
        recordButton.setOnClickListener { toggleRecording() }
        configureObd()
        enterCarFlow()
    }

    private fun enterCarFlow() {
        val cars = carStore.all()
        when (cars.size) {
            0 -> showFirstCarDialog()
            1 -> { carStore.select(cars[0].id); refreshCarUi() }
            else -> showCarsDialog()
        }
    }

    private fun showFirstCarDialog() {
        AlertDialog.Builder(this).setTitle("Добавьте автомобиль")
            .setMessage("Чтобы начать сбор информации, сначала добавьте автомобиль.")
            .setPositiveButton("Добавить автомобиль") { _, _ -> showCarEditor(carStore.create(), true) }
            .setCancelable(false).show()
    }

    private fun addCarAndReturnToDiagnostic() = showCarEditor(carStore.create(), true)

    fun hasLocationPermission(): Boolean = if (Build.VERSION.SDK_INT < 23) true else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshCarUi() {
        val car = carStore.selected()
        if (car == null) { currentCarText.text = "Автомобиль не выбран"; connectButton.isEnabled=false; recordButton.isEnabled=false; return }
        currentCarText.text = "${car.title()}\n${car.subtitle().ifBlank { "Параметры не заполнены" }}"
        connectButton.isEnabled=true; recordButton.isEnabled=true
    }

    private fun configureObd() {
        if (::obd.isInitialized) obd.close()
        obd = TargetElm327Ble(this, object : TargetElm327Ble.Listener {
            override fun onState(text: String) = runOnUiThread { status.text=text }
            override fun onData(values: ObdValues, monotonicNs: Long) { runOnUiThread { obdValues.text=values.toDisplay() }; recorder?.onObd(values, monotonicNs) }
            override fun onDevices(devices: List<TargetElm327Ble.DeviceInfo>) = runOnUiThread { showObdDevices(devices) }
        })
        status.text = "OBD-адаптер не подключён — запись возможна с GPS"
    }

    private fun showObdDevices(devices: List<TargetElm327Ble.DeviceInfo>) {
        if (devices.isEmpty()) { AlertDialog.Builder(this).setTitle("OBD-адаптеры не найдены").setMessage("Проверьте питание ELM327 и Bluetooth.").setPositiveButton("OK", null).show(); return }
        val labels = devices.map { it.label() }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Выберите OBD-адаптер")
            .setSingleChoiceItems(labels, -1) { dialog, which -> val selected=devices[which]; dialog.dismiss(); status.text="Подключение: ${selected.label().replace("\n", " — ")}"; obd.connect(selected.device) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showCarsDialog() {
        val cars=carStore.all(); if(cars.isEmpty()){showFirstCarDialog();return}
        val labels=cars.map{"${it.title()}${it.subtitle().let{s->if(s.isBlank())"" else "\n$s"}}"}.toTypedArray(); val selected=cars.indexOfFirst{it.id==carStore.selectedId()}.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Выберите автомобиль").setSingleChoiceItems(labels,selected){dialog,which->carStore.select(cars[which].id);refreshCarUi();dialog.dismiss()}
            .setNeutralButton("Изменить"){_,_->showCarEditor(cars[selected],false)}.setPositiveButton("Добавить"){_,_->addCarAndReturnToDiagnostic()}.setNegativeButton("Закрыть",null).show()
    }

    private fun showCarEditor(car: Car, returnToDiagnostic: Boolean) {
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(40,8,40,0)}
        fun field(hint:String,value:String="")=EditText(this).apply{this.hint=hint;setText(value);setSingleLine(true);form.addView(this)}
        val make=field("Марка",car.make);val model=field("Модель",car.model);val year=field("Год выпуска",car.year);val engine=field("Двигатель / объём",car.engine);val fuel=field("Топливо",car.fuel);val transmission=field("Коробка передач",car.transmission);val drive=field("Привод",car.drive);val mileage=field("Пробег, км",car.mileage);val vin=field("VIN (необязательно)",car.vin);val plate=field("Госномер (необязательно)",car.plate);val notes=field("Дополнительная информация",car.notes)
        val scroll=android.widget.ScrollView(this).apply{addView(form)}
        AlertDialog.Builder(this).setTitle(if(car.make.isBlank()&&car.model.isBlank())"Добавить автомобиль" else "Автомобиль").setView(scroll).setNegativeButton("Отмена"){_,_->if(returnToDiagnostic&&carStore.all().isEmpty())showFirstCarDialog()}
            .setPositiveButton("Сохранить"){_,_->car.make=make.text.toString().trim();car.model=model.text.toString().trim();car.year=year.text.toString().trim();car.engine=engine.text.toString().trim();car.fuel=fuel.text.toString().trim();car.transmission=transmission.text.toString().trim();car.drive=drive.text.toString().trim();car.mileage=mileage.text.toString().trim();car.vin=vin.text.toString().trim();car.plate=plate.text.toString().trim();car.notes=notes.text.toString().trim();if(car.make.isBlank()||car.model.isBlank())return@setPositiveButton;carStore.save(car);carStore.select(car.id);refreshCarUi()}.show()
    }

    private fun showHistory(){val car=carStore.selected()?:run{showFirstCarDialog();return};val records=recordStore.forCar(car.id);val text=if(records.isEmpty())"Диагностик пока нет." else records.joinToString("\n\n"){val date=SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(Date(it.createdAt));"${date}\n${it.complaint.ifBlank{"Без описания неисправности"}}\nСессия: ${it.sessionName}"};AlertDialog.Builder(this).setTitle("История — ${car.title()}").setMessage(text).setPositiveButton("Закрыть",null).show()}

    private fun requestAndConnect(){val permissions=mutableListOf<String>();if(Build.VERSION.SDK_INT>=31){permissions+=Manifest.permission.BLUETOOTH_SCAN;permissions+=Manifest.permission.BLUETOOTH_CONNECT};pendingConnect=true;if(permissions.any{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED})requestPermissions(permissions.toTypedArray(),requestCode)else{configureObd();obd.scan()}}
    override fun onRequestPermissionsResult(request:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(request,permissions,results);if(request==requestCode&&pendingConnect&&results.all{it==PackageManager.PERMISSION_GRANTED}){configureObd();obd.scan()};pendingConnect=false}

    private fun toggleRecording(){
        val car=carStore.selected()?:run{showFirstCarDialog();return}
        if(recorder==null){
            val needed=if(Build.VERSION.SDK_INT>=23)arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.ACCESS_FINE_LOCATION)else arrayOf(Manifest.permission.RECORD_AUDIO)
            if(needed.any{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}){pendingConnect=false;requestPermissions(needed,requestCode);return}
            val r=PublicSessionRecorder(this,car){text->runOnUiThread{recordStatus.text=text}};recorder=r;r.start();recordButton.text="ОСТАНОВИТЬ ЗАПИСЬ"
        }else{
            val r=recorder?:return;r.stop();lastSessionUris=r.sessionUris();recorder=null;recordButton.text="НАЧАТЬ ЗАПИСЬ";showPostRecordDialog(car,r.sessionName)
        }
    }

    private fun showPostRecordDialog(car:Car,sessionName:String){
        val input=EditText(this).apply{hint="Что вас беспокоит? (необязательно)";minLines=4;gravity=Gravity.TOP}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(32,8,32,0);addView(input)}
        AlertDialog.Builder(this).setTitle("Диагностическая сессия завершена")
            .setMessage("${car.title()}\nСессия: $sessionName\n\nЗапись сохранена. Можно передать её ИИ для анализа.")
            .setView(box)
            .setNegativeButton("Закрыть"){_,_->recordStore.add(car.id,sessionName,input.text.toString().trim())}
            .setPositiveButton("АНАЛИЗИРОВАТЬ ИИ"){_,_->recordStore.add(car.id,sessionName,input.text.toString().trim());shareSessionWithAi(car,sessionName,input.text.toString().trim())}
            .show()
    }

    private fun shareSessionWithAi(car:Car,sessionName:String,complaint:String){
        if(lastSessionUris.isEmpty()){AlertDialog.Builder(this).setTitle("Данные недоступны").setMessage("Не удалось получить файлы диагностической сессии.").setPositiveButton("ОК",null).show();return}
        val prompt="""
Проанализируй диагностическую сессию автомобиля и дай техническое заключение.
Автомобиль: ${car.title()}
Параметры: ${car.subtitle()}
Сессия: $sessionName
Жалоба владельца: ${complaint.ifBlank { "не указана" }}

Во вложениях audio.wav, obd.csv, gps.csv, sensors.csv, session.json.
Сопоставь звук, OBD, GPS и датчики по общей временной шкале. Найди условия возникновения неисправности, отличай корреляцию от доказательства причины, укажи наиболее вероятные группы причин и конкретные проверки. Не утверждай конкретную неисправность без достаточных данных.
""".trimIndent()
        val intent=Intent(Intent.ACTION_SEND_MULTIPLE).apply{type="application/octet-stream";putExtra(Intent.EXTRA_TEXT,prompt);putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(lastSessionUris));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        startActivity(Intent.createChooser(intent,"Передать диагностическую сессию в ИИ"))
    }

    override fun onDestroy(){recorder?.stop();recorder=null;if(::obd.isInitialized)obd.close();super.onDestroy()}
}
