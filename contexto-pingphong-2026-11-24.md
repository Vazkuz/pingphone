# Ping Phone — contexto del proyecto

Juego de ping pong en el navegador de la laptop, controlado con el celular como si fuera una paleta (mando tipo Wii): la APK manda la orientación y la aceleración del celular, y la cámara de la laptop sigue la muñeca del jugador para saber dónde está la paleta.

- Última actualización: 24/11/2026 (America/Lima)
- Versión de este documento: v1
- Carpeta del proyecto: `C:\Users\canad\Desktop\Daniel M\Projects\PingPhone`
  (hasta el 23/11 se llamaba `PingPhong`; Daniel la renombró. El 25/11 el nombre visible pasó a "Ping Phone" en la página, el servidor y la APK. Siguen con el nombre viejo, a propósito: el paquete `com.example.pingphong`, la clase `PingPhongTheme`, el estilo `Theme.PingPhong` y la clave `pingphong.` del almacenamiento del navegador.)

## Arranque en frío

Ping Phone (antes PingPhong; renombrado el 25/11/2026) es un proyecto personal de Daniel. Funciona en local: un servidor Node en la laptop, una página web con Three.js que dibuja una paleta 3D, y una APK en Kotlin que manda los sensores del celular por WiFi. Hoy hay un modo de práctica: llegan pelotas hacia el jugador y él las responde. Todavía no hay mesa, ni rival, ni segundo jugador.

El último cambio fue **el avance de la paleta en profundidad usando el acelerómetro** (al hacer el gesto de golpear, la paleta avanza hacia la pelota y luego vuelve). Daniel todavía no lo probó.

Primera acción concreta: pedir acceso a la carpeta del proyecto, leer `servidor/index.html` (sobre todo los bloques "Avance por acelerómetro", "Filtro de la posición" y "Pelotas de práctica") y preguntarle a Daniel cómo se sintió el avance al golpear. No escribir código antes de tener esa respuesta.

## Objetivo y criterio de éxito

- **Objetivo final:** un ping pong para dos jugadores, cada uno con su celular como paleta, frente a una laptop que muestra el juego. Que la gente pueda entrar rápido: descargar una APK (le parece normal y conocido) pero **no instalar nada en la laptop**.
- **Publicación (más adelante):** la página en internet (Vercel, que Daniel ya usa) y conexión celular–laptop por WebRTC con emparejamiento por Supabase (también lo usa ya). Así desaparece el `node server.js`. Daniel piensa comprar un dominio propio para sus proyectos y poner este como parte de un portafolio (dominio: pendiente de confirmar).
- **Criterio de éxito de la etapa actual:** que responder las pelotas de práctica "se sienta" como en el ping pong real. Lo aprueba Daniel jugando.

## Estado actual

**Hecho y probado por Daniel:**
- APK en su celular: lee sensores, encuentra salas del WiFi solo y entra con un toque (sin IPs).
- Servidor local y página: la paleta 3D sigue la orientación del celular en tiempo real (~58-100 paquetes/s).
- Calibración guiada de 5 pasos desde el celular, con dibujos. Dijo que los dibujos "se entienden a la perfección".
- Paleta de ping pong (roja del lado de la pantalla, negra atrás, mango de madera). "Se ve genial".
- Seguimiento de muñeca con la cámara: 27-28 detecciones/s con el motor en CPU.
- Pelotas de práctica, contador "Respondidas", vibración solo al golpear una pelota.
- Vista recta detrás de la paleta, suelo con rejilla, sombras y aros de llegada. Dijo "bastante mejor".

**Hecho, sin probar por Daniel:**
- Avance de la paleta por acelerómetro (página + APK: la APK ahora manda el instante de cada lectura). Probado solo con un celular simulado: un golpe de 30 cm se estimó entre 27 y 30 cm.
- Ajuste "Avance al golpear" en el panel (0 % a 300 %).

**No hecho:** mesa, física con rebote en la mesa, rival o segundo jugador en pantalla, reglas y puntaje, publicación en Vercel/WebRTC, mantener la configuración de calibración entre recargas (hoy se pierde al recargar la página).

## Cómo trabajar conmigo

**Cómo me comunico**
- Español, informal, mensajes cortos y a veces con errores de tipeo. Cuando algo falla puedo putear: es frustración con el problema, no hace falta comentarlo.
- Pruebo yo mismo y respondo con capturas de pantalla, fotos del celular o videos de la laptop (.mp4). Del video conviene sacar cuadros y mirar los números del panel.
- Tengo formación en física y en diseño de videojuegos (máster). Sé programar. Sé usar la terminal y git: no me expliques comandos básicos.
- Tengo TDAH.

**Cómo espero las respuestas**
- Cortas, un paso a la vez, terminando con **una** acción concreta ("Siguiente: …").
- Cuando pido una explicación: breve, sin ser súper técnico, pero **sin analogías**. Definiciones correctas.
- Cuando pregunto costos o límites: **datos concretos con cifras y fuente**. Me molestó una respuesta genérica ("no me estás dando datos").
- Antes de un cambio de diseño con varias formas posibles: dame opciones en tabla con recomendación, y espera mi elección. Para correcciones claras, hazlas directo.
- Si algo que dije no se cumplió, reconócelo explícito ("disculpa, tienes razón") y corrígelo. Ejemplo real: dejé claro que las calibraciones van en el celular y quedó un botón "Recentrar" en la laptop; me enojé con razón.
- No uses texto ambiguo para indicar posturas físicas. "Sostén el celular de canto" no se entendió; los **dibujos** sí.
- Di siempre **qué probaste y qué no**. Desde el entorno de Claude no hay celular, ni cámara, ni Android Studio: la APK no se puede compilar ahí, y lo que dependa de la cámara o del sensor real solo lo pruebo yo.
- Prefiero que trabajes **directo en la carpeta conectada** (no pegarme código para copiar). Después de escribir un archivo, verifica que quedó (tamaño o relectura): una vez la copia en el disco no coincidía.
- Para probar me basta: "Ctrl+F5 en la página", "Ctrl+C y `node server.js`", "Run en Android Studio". Avisa cuáles de los tres hacen falta en cada cambio.

## Componentes

| Pieza | Archivo | Qué hace |
|---|---|---|
| APK | `app/src/main/java/com/example/pingphong/MainActivity.kt` | Lee Game Rotation Vector y Linear Acceleration, busca salas por difusión UDP, manda sensores por UDP, vibra, muestra la calibración a pantalla completa. Jetpack Compose. Botones de volumen = calibrar/confirmar. |
| Dibujos de calibración | `app/src/main/res/drawable/cal_paso1.xml` … `cal_paso5.xml` | VectorDrawables. Los mismos dibujos van como SVG dentro de `index.html`. Se generaron con un script Python (no está en la carpeta): si se cambian, cambiar los dos lados. |
| Permisos | `app/src/main/AndroidManifest.xml` | INTERNET, VIBRATE, ACCESS_LOCAL_NETWORK (Android 17 lo exige; la APK lo pide al abrir). |
| Servidor | `servidor/server.js` | Node sin dependencias. UDP 41234 para los celulares, HTTP 3000 para la página (página, eventos SSE, `/vibrar`, `/paso`). Elige nombre de sala al azar entre 20, sin repetir los de otras laptops del WiFi. Máximo 2 jugadores. |
| Juego | `servidor/index.html` | Three.js 0.160 y MediaPipe Pose Landmarker (lite) desde CDN. Paleta, cámara del juego, pelotas, hitbox, calibración, filtro de posición, avance por acelerómetro, panel de ajustes. |

### Protocolo (texto por UDP, separado por comas)

- Celular → laptop: `buscar` · `qx,qy,qz,qw,ax,ay,az,marca_ms` (sensores) · `cal` (calibrar / confirmar paso, se manda dos veces) · `calx` (salir de la calibración).
- Laptop → celular: `sala,<nombre>` · `ok,<jugador>,<paso>,<aviso>` (cada segundo) · `llena` · `v,<ms>` (vibrar) · `p,<paso>,<aviso>`.
- `aviso`: 0 ninguno, 1 cámara apagada, 2 la cámara no ve a nadie, 3 no ve la muñeca.
- Página → servidor por HTTP: `/vibrar?j=1&ms=…`, `/paso?j=1&n=…&e=…`. Servidor → página por SSE: `s,<sala>`, `c,<jugador>,<0|1>`, `d,<jugador>,<datos>`, `k,<jugador>` (pulsó calibrar), `x,<jugador>` (salió).

### Cómo funciona por dentro

- **Ejes:** Android (X este, Y norte, Z arriba) → Three (`x, z, -y`). En la paleta: +Y = lado de la pantalla (goma roja), −Z = borde superior del celular (punta de la pala), origen en el mango.
- **Calibración (5 pasos, se maneja solo desde el celular):** 1) celular apuntando a la laptop como control remoto, pantalla arriba → fija el giro horizontal (`qCentro`) y el centro de la imagen; 2) brazo a la izquierda; 3) a la derecha → escala horizontal; 4) arriba; 5) abajo hasta las rodillas → escala vertical. Si la cámara no ve la muñeca el paso no avanza y el celular muestra el aviso.
- **Posición:** muñeca del modelo de pose → filtro One Euro (corteMin 0,3 Hz, beta 8) → adelanto de 50 ms con la velocidad para compensar el retraso → escala de la calibración × ajustes "Recorrido horizontal/vertical".
- **Profundidad:** doble integración de la aceleración en el eje jugador–mesa, solo durante el gesto (umbral 1,5 m/s²); en reposo la velocidad se apaga y la paleta vuelve a su plano en ~0,45 s; el sesgo del sensor se aprende en reposo; un "gesto" de más de 1 s se descarta como error. Escala: 4 unidades de juego = 1 m.
- **Golpe:** contacto con el disco de la pala (hitbox 30 % mayor que el dibujo, el mango no cuenta), comprobando el cruce entre dos cuadros en el sistema de la paleta. Rebote reflejado en la cara de la pala + velocidad de la pala. Vibración de 40-120 ms según el impacto.
- **Panel (se puede ocultar):** tamaño de la paleta (inicial 150 %), recorrido horizontal, recorrido vertical, avance al golpear; todos con barra y botones − y +, guardados en el navegador. Además: estado de cámara, cuadros y detecciones por segundo, ms por detección, motor CPU/GPU, selector de cámara, ocultar la imagen.

## Entorno de Daniel

- Laptop Windows, Node v24.18.0, Android Studio Rabbit 2. Cámara integrada "ACER HD User Facing"; hay cámaras virtuales instaladas (DroidCam, NVIDIA Broadcast, OBS), por eso existe el selector.
- Navegador: en el video del 22/11 parece Opera GX (sin confirmar).
- Celular Android conectado por USB con depuración USB. Para que Android Studio lo vea hubo que poner el USB en "Transferencia de archivos". Modelo (pendiente de confirmar).
- Medidas: detección de pose 20-30 ms en CPU contra 50-65 ms en GPU (por eso CPU es el motor por defecto). Aceleración de un golpe normal ~48 m/s², fuerte ~164.
- Juega en la sala de su casa, a unos 2 m de la laptop, con la imagen tomándolo de rodillas a cabeza.

## Decisiones tomadas

- **APK nativa en vez de mando web:** más fidelidad de sensores.
- **Juego en el navegador:** sencillo y no requiere instalar nada en la laptop.
- **Laptop como servidor / anfitrión** (no el celular): con dos jugadores es más simple.
- **Primero local, publicar después.** La versión local usa Node + UDP + SSE; al publicar se reemplaza por WebRTC + Supabase y la página en Vercel. Sensores, calibración y juego se conservan.
- **Salas con nombre al azar** de 20 (incluye Daniel, Arantxa y Vasco) y botones en el celular, sin IPs. Queda un enlace discreto "Conectar por IP" por si un router bloquea la difusión.
- **Toda calibración se hace desde el celular.** En la laptop no hay botón ni tecla de calibrar.
- **Vista desde atrás del propio lado.** La vista lateral es inviable ("sería injugable").
- **Golpe por contacto** con hitbox generoso; el mango no golpea.
- **Cámara como Kinect** (idea traída de su proyecto Pinch): se sigue la muñeca con el modelo de pose, no con el de manos, porque la mano que sostiene el celular queda tapada.
- **Profundidad por acelerómetro primero**, cámara después solo si hace falta.

## Restricciones y "no hacer"

- No volver a poner calibración en la laptop.
- No indicar posturas solo con texto: usar dibujos.
- No mostrar IPs como forma principal de entrar a una sala.
- No pedir a los jugadores instalar nada en la laptop (versión publicada).
- Bluetooth para medir distancia celular–laptop: descartado (la potencia de la señal tiene error de metros y varía al girar el celular). UWB no está en su laptop.
- Motor GPU de MediaPipe: más lento en su laptop; dejar CPU por defecto.

## Siguientes pasos

1. Que Daniel pruebe el avance por acelerómetro (Ctrl+F5 + Run) y diga si avanza al golpear y si avanza solo cuando no debe (por ejemplo al girar la muñeca). Afinar `ACEL_GESTO`, `OLVIDO_*` o el ajuste según lo que cuente.
2. Si falta poder adelantar la posición de espera: usar la profundidad que da el modelo de pose (muñeca respecto a la cadera) para movimientos lentos, combinada con el acelerómetro.
3. Guardar la calibración en el navegador para no repetirla en cada recarga.
4. Mesa y física básica (rebote en la mesa, red), manteniendo sombras y aros.
5. Segundo jugador: pantalla dividida (cada uno ve desde atrás de su lado).
6. Publicar: página en Vercel, WebRTC con emparejamiento por Supabase, eliminar el servidor Node.

## Preguntas abiertas

- ¿Cómo se siente el avance por acelerómetro? (pendiente de confirmar)
- ¿Por qué se renombró la carpeta a `PingPhone`? ¿Cambió algo más del proyecto? (pendiente de confirmar)
- ¿Qué tamaño de paleta y recorridos le resultan cómodos? (pendiente de confirmar)
- Modelo del celular y navegador que usa para jugar. (pendiente de confirmar)
- ¿Las pelotas de práctica se mantienen como modo entrenamiento cuando exista el juego? (pendiente de confirmar)
