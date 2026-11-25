package com.example.pingphong

import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.pingphong.ui.theme.PingPhongTheme
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.math.sqrt

private const val PUERTO_SERVIDOR = 41234
private const val PREF_IP = "ip_servidor"

// Android 17 exige este permiso para hablar con equipos de la red WiFi.
private const val PERMISO_RED_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK"
private const val ANDROID_17 = 37

private const val BUSCAR_CADA_MS = 1000L
private const val SALA_CADUCA_MS = 3500L
private const val SIN_RESPUESTA_MS = 3000L

// Los mismos pasos y dibujos que muestra la laptop durante la calibración (índice = paso).
private val TEXTO_PASO = listOf(
    "",
    "Apunta el celular hacia la laptop, como un control remoto, con la pantalla hacia arriba.",
    "Sin mover los pies, lleva el celular hacia tu izquierda todo lo que alcances.",
    "Ahora llévalo hacia tu derecha todo lo que alcances.",
    "Súbelo todo lo que alcances.",
    "Bájalo hasta las rodillas. Tu mano tiene que verse en la cámara.",
)
private val DIBUJO_PASO = listOf(
    R.drawable.cal_paso1,
    R.drawable.cal_paso1,
    R.drawable.cal_paso2,
    R.drawable.cal_paso3,
    R.drawable.cal_paso4,
    R.drawable.cal_paso5,
)
private val VISTA_PASO = listOf(
    "",
    "Vista desde arriba",
    "Vista desde arriba",
    "Vista desde arriba",
    "Vista de frente",
    "Vista de frente",
)
private const val PASOS_CALIBRACION = 5

// Problemas de la cámara de la laptop que se avisan durante la calibración (índice = código).
private val AVISO_CAMARA = listOf(
    "",
    "La cámara de la laptop está apagada. Actívala en la laptop; si confirmas así, solo se calibra la orientación.",
    "La cámara no ve a nadie. Ponte frente a la laptop, a unos 2 metros.",
    "La cámara no ve tu mano. Aléjate o inclina la pantalla de la laptop hasta que se vea.",
)

/** Una laptop con el juego abierto en el mismo WiFi. */
private data class Sala(val nombre: String, val direccion: InetAddress, val visto: Long)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var hiloSensores: HandlerThread
    private lateinit var handlerSensores: Handler

    // Estado que muestra la pantalla
    private var salas by mutableStateOf(listOf<Sala>())
    private var salaActual by mutableStateOf<String?>(null) // null = eligiendo sala
    private var jugador by mutableStateOf<String?>(null)    // null = aún sin respuesta
    private var salaLlena by mutableStateOf(false)
    private var pasoCalibracion by mutableStateOf(0)        // 0 = sin calibración en curso
    private var avisoCamara by mutableStateOf(0)            // 0 = sin problema con la cámara
    private var faltaPermiso by mutableStateOf(false)
    private var mostrarIp by mutableStateOf(false)
    private var ipManual by mutableStateOf("")
    private var avisoIp by mutableStateOf("")
    private var yaw by mutableStateOf(0f)
    private var pitch by mutableStateOf(0f)
    private var roll by mutableStateOf(0f)
    private var accel by mutableStateOf(0f)

    // Red
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var destino: InetAddress? = null // laptop de la sala elegida
    @Volatile private var generacion = 0               // invalida el hilo de red anterior
    @Volatile private var ultimoOk = 0L
    private var permisoPedido = false
    private var redActiva = false                      // solo desde el hilo de la pantalla

    // Última lectura de cada sensor (solo se tocan desde hiloSensores)
    private val cuaternion = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z
    private val aceleracion = FloatArray(3)
    private var marcaAceleracion = 0L   // instante de la última lectura de aceleración (ms)
    private val matriz = FloatArray(9)
    private val angulos = FloatArray(3)
    private val texto = StringBuilder(128)

    private val pedirRedLocal =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            faltaPermiso = !concedido
            if (concedido) {
                iniciarRed()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        hiloSensores = HandlerThread("sensores").also { it.start() }
        handlerSensores = Handler(hiloSensores.looper)
        ipManual = getPreferences(MODE_PRIVATE).getString(PREF_IP, "") ?: ""

        setContent {
            PingPhongTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val sala = salaActual
                    val paso = pasoCalibracion
                    if (!faltaPermiso && sala != null && paso in 1..PASOS_CALIBRACION) {
                        // Durante la calibración la pantalla muestra solo la calibración.
                        PantallaCalibracion(paso)
                    } else {
                        Column(modifier = Modifier.safeDrawingPadding().padding(24.dp)) {
                            Text("Ping Phone", style = MaterialTheme.typography.headlineMedium)
                            if (faltaPermiso) {
                                PantallaPermiso()
                            } else if (sala == null) {
                                PantallaSalas()
                            } else {
                                PantallaJuego(sala)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- Pantallas ----------

    @Composable
    private fun PantallaPermiso() {
        Text(
            "Para encontrar las salas hace falta el permiso de red local.",
            modifier = Modifier.padding(top = 16.dp)
        )
        Button(
            onClick = { pedirRedLocal.launch(PERMISO_RED_LOCAL) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Dar permiso") }
    }

    @Composable
    private fun PantallaSalas() {
        Text(
            "Salas disponibles",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        if (salas.isEmpty()) {
            Text("Buscando salas en tu WiFi…", modifier = Modifier.padding(top = 8.dp))
        }
        for (sala in salas) {
            Button(
                onClick = { entrar(sala.nombre, sala.direccion) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(sala.nombre, style = MaterialTheme.typography.titleLarge) }
        }

        TextButton(
            onClick = { mostrarIp = !mostrarIp },
            modifier = Modifier.padding(top = 24.dp)
        ) { Text("¿No aparece tu sala? Conectar por IP") }
        if (mostrarIp) {
            OutlinedTextField(
                value = ipManual,
                onValueChange = { ipManual = it.trim() },
                label = { Text("IP de la laptop") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = { entrarPorIp() },
                enabled = ipManual.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Conectar") }
            if (avisoIp.isNotEmpty()) Text(avisoIp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun PantallaJuego(sala: String) {
        Text(
            "Sala $sala",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        val numero = jugador
        Text(
            when {
                salaLlena -> "La sala ya tiene dos jugadores"
                numero != null -> "Conectado: jugador $numero"
                else -> "Conectando…"
            },
            modifier = Modifier.padding(top = 4.dp)
        )
        Button(
            onClick = { calibrar() },
            enabled = numero != null,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
        ) {
            Text(
                "Calibrar",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        }
        Text(
            "Ponte en tu posición de juego y pulsa Calibrar o un botón de volumen.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        OutlinedButton(
            onClick = { salir() },
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
        ) { Text("Salir de la sala") }

        Text(
            "Sensores",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text("Yaw %.0f°   Pitch %.0f°   Roll %.0f°".format(yaw, pitch, roll))
        Text("Aceleración %.1f m/s²".format(accel))
    }

    @Composable
    private fun PantallaCalibracion(paso: Int) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Calibración · paso $paso de $PASOS_CALIBRACION",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = { salirCalibracion() }) { Text("Salir de la calibración") }
            }

            Image(
                painter = painterResource(DIBUJO_PASO[paso]),
                contentDescription = TEXTO_PASO[paso],
                // Fondo oscuro fijo: el dibujo usa colores claros, igual que en la laptop.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(Color(0xFF14161A), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            )
            Text(VISTA_PASO[paso], style = MaterialTheme.typography.bodySmall)

            Text(
                TEXTO_PASO[paso],
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )

            // Aviso si la cámara de la laptop no puede ver al jugador: así no hace falta
            // mirar la laptop para saber por qué la calibración no avanza.
            val aviso = AVISO_CAMARA.getOrElse(avisoCamara) { "" }
            if (aviso.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Text(
                        aviso,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = { calibrar() },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
            ) {
                Text(
                    "Confirmar posición",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            }
        }
    }

    // ---------- Salas ----------

    private fun entrar(nombre: String, direccion: InetAddress) {
        ultimoOk = SystemClock.elapsedRealtime()
        jugador = null
        salaLlena = false
        salaActual = nombre
        destino = direccion
    }

    private fun entrarPorIp() {
        val ip = ipManual
        getPreferences(MODE_PRIVATE).edit().putString(PREF_IP, ip).apply()
        avisoIp = ""
        Thread {
            try {
                val direccion = InetAddress.getByName(ip)
                runOnUiThread { entrar(ip, direccion) }
            } catch (e: Exception) {
                avisoIp = "IP no válida"
            }
        }.start()
    }

    private fun salir() {
        pasoCalibracion = 0
        destino = null
        salaActual = null
        jugador = null
        salaLlena = false
    }

    // ---------- Red ----------

    private fun tienePermisoRed(): Boolean =
        Build.VERSION.SDK_INT < ANDROID_17 ||
            checkSelfPermission(PERMISO_RED_LOCAL) == PackageManager.PERMISSION_GRANTED

    private fun iniciarRed() {
        if (redActiva) return
        redActiva = true
        val miGeneracion = ++generacion
        ultimoOk = SystemClock.elapsedRealtime()
        Thread({
            val nuevo = try {
                DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 500
                }
            } catch (e: Exception) {
                null
            }
            if (nuevo != null) {
                socket = nuevo
                bucleRed(nuevo, miGeneracion)
                if (socket === nuevo) socket = null
                nuevo.close()
            }
        }, "red").start()
    }

    private fun detenerRed() {
        redActiva = false
        generacion++
        socket?.close()
        salas = emptyList()
        jugador = null
    }

    /**
     * Hilo de red. Sin sala elegida, pregunta cada segundo qué salas hay.
     * Siempre escucha las respuestas de las laptops:
     *   "sala,<nombre>"  una laptop anuncia su sala
     *   "ok,<jugador>,<paso>,<aviso>"  la sala confirma la conexión (cada segundo)
     *   "llena"          la sala ya tiene dos jugadores
     *   "v,<ms>"         vibrar
     *   "p,<paso>,<aviso>"  paso de la calibración (0 = ninguno) y problema de la cámara (0 = ninguno)
     */
    private fun bucleRed(s: DatagramSocket, miGeneracion: Int) {
        val buffer = ByteArray(128)
        val paquete = DatagramPacket(buffer, buffer.size)
        var ultimaBusqueda = 0L

        while (generacion == miGeneracion) {
            val ahora = SystemClock.elapsedRealtime()

            if (destino == null && ahora - ultimaBusqueda >= BUSCAR_CADA_MS) {
                ultimaBusqueda = ahora
                preguntarSalas(s)
                val vivas = salas.filter { ahora - it.visto < SALA_CADUCA_MS }
                if (vivas.size != salas.size) salas = vivas
            }
            if (destino != null && jugador != null && ahora - ultimoOk > SIN_RESPUESTA_MS) {
                jugador = null
            }

            try {
                paquete.setData(buffer, 0, buffer.size)
                s.receive(paquete)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break // el socket se cerró
            }

            val origen = paquete.address
            val partes = String(buffer, 0, paquete.length, Charsets.UTF_8).split(',')
            val esMiSala = origen == destino
            when (partes[0]) {
                "sala" -> {
                    val nombre = partes.getOrNull(1)
                    if (nombre != null && generacion == miGeneracion) {
                        val resto = salas.filter { it.direccion != origen }
                        salas = (resto + Sala(nombre, origen, SystemClock.elapsedRealtime()))
                            .sortedBy { it.nombre }
                    }
                }
                "ok" -> if (esMiSala) {
                    ultimoOk = SystemClock.elapsedRealtime()
                    salaLlena = false
                    jugador = partes.getOrNull(1) ?: "?"
                    // El "ok" repite el paso de calibración por si el aviso suelto se perdió.
                    partes.getOrNull(2)?.toIntOrNull()?.let { pasoCalibracion = it }
                    partes.getOrNull(3)?.toIntOrNull()?.let { avisoCamara = it }
                }
                "llena" -> if (esMiSala) {
                    salaLlena = true
                    jugador = null
                }
                "v" -> if (esMiSala) {
                    vibrar(partes.getOrNull(1)?.toLongOrNull() ?: 60L)
                }
                "p" -> if (esMiSala) {
                    pasoCalibracion = partes.getOrNull(1)?.toIntOrNull() ?: 0
                    avisoCamara = partes.getOrNull(2)?.toIntOrNull() ?: 0
                }
            }
        }
    }

    /** Envía "buscar" a todo el WiFi; cada laptop con el juego abierto responde. */
    private fun preguntarSalas(s: DatagramSocket) {
        val datos = "buscar".toByteArray(Charsets.UTF_8)
        for (direccion in direccionesDifusion()) {
            try {
                s.send(DatagramPacket(datos, datos.size, direccion, PUERTO_SERVIDOR))
            } catch (e: Exception) {
                // Esa red no admite difusión: se prueba la siguiente.
            }
        }
    }

    private fun direccionesDifusion(): List<InetAddress> {
        val lista = mutableListOf<InetAddress>()
        try {
            lista.add(InetAddress.getByName("255.255.255.255"))
            val redes = NetworkInterface.getNetworkInterfaces() ?: return lista
            for (red in redes) {
                if (!red.isUp || red.isLoopback) continue
                for (dir in red.interfaceAddresses) {
                    val difusion = dir.broadcast
                    if (difusion != null && difusion !in lista) lista.add(difusion)
                }
            }
        } catch (e: Exception) {
            // Sin red: la lista queda como esté.
        }
        return lista
    }

    private fun enviar() {
        val s = socket ?: return
        val d = destino ?: return
        texto.setLength(0)
        texto.append(cuaternion[1]).append(',')
            .append(cuaternion[2]).append(',')
            .append(cuaternion[3]).append(',')
            .append(cuaternion[0]).append(',')
            .append(aceleracion[0]).append(',')
            .append(aceleracion[1]).append(',')
            .append(aceleracion[2]).append(',')
            .append(marcaAceleracion)
        val bytes = texto.toString().toByteArray(Charsets.US_ASCII)
        try {
            s.send(DatagramPacket(bytes, bytes.size, d, PUERTO_SERVIDOR))
        } catch (e: Exception) {
            // Sin red o socket cerrado: se descarta este paquete.
        }
    }

    /** Empieza la calibración o confirma el paso que se está mostrando. */
    private fun calibrar() = avisar("cal")

    private fun salirCalibracion() {
        pasoCalibracion = 0
        avisoCamara = 0
        avisar("calx")
    }

    /** Manda un aviso corto a la laptop, dos veces por si un paquete se pierde. */
    private fun avisar(mensaje: String) {
        handlerSensores.post {
            val s = socket ?: return@post
            val d = destino ?: return@post
            val datos = mensaje.toByteArray(Charsets.US_ASCII)
            try {
                s.send(DatagramPacket(datos, datos.size, d, PUERTO_SERVIDOR))
                s.send(DatagramPacket(datos, datos.size, d, PUERTO_SERVIDOR))
            } catch (e: Exception) {
                // Sin red: el aviso se pierde.
            }
        }
    }

    // Dentro de una sala, los botones de volumen calibran (se pueden pulsar sin mirar la pantalla).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val esVolumen = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (esVolumen && destino != null) {
            if (event?.repeatCount == 0) calibrar()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibrar(ms: Long) {
        val vibrador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrador.vibrate(
            VibrationEffect.createOneShot(ms.coerceIn(10L, 500L), VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    // ---------- Ciclo de vida y sensores ----------

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handlerSensores)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handlerSensores)
        }

        if (tienePermisoRed()) {
            faltaPermiso = false
            iniciarRed()
        } else if (!permisoPedido) {
            permisoPedido = true
            pedirRedLocal.launch(PERMISO_RED_LOCAL)
        } else {
            faltaPermiso = true
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        detenerRed()
    }

    override fun onDestroy() {
        super.onDestroy()
        hiloSensores.quitSafely()
    }

    // Se ejecuta en hiloSensores, no en el hilo de la pantalla.
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getQuaternionFromVector(cuaternion, event.values)
                SensorManager.getRotationMatrixFromVector(matriz, event.values)
                SensorManager.getOrientation(matriz, angulos)
                yaw = Math.toDegrees(angulos[0].toDouble()).toFloat()
                pitch = Math.toDegrees(angulos[1].toDouble()).toFloat()
                roll = Math.toDegrees(angulos[2].toDouble()).toFloat()
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                aceleracion[0] = event.values[0]
                aceleracion[1] = event.values[1]
                aceleracion[2] = event.values[2]
                marcaAceleracion = event.timestamp / 1_000_000L
                val (x, y, z) = aceleracion
                accel = sqrt(x * x + y * y + z * z)
            }
        }
        enviar()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
