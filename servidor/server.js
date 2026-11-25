// Servidor local de Ping Phone (sin dependencias).
// Celular con la app  --UDP-->        este servidor --SSE--> navegador de la laptop.
// Celular con el navegador --WebSocket (HTTPS)--> este servidor --SSE--> navegador de la laptop.
// Uso: node server.js   y luego abrir http://localhost:3000

const dgram = require('node:dgram');
const http = require('node:http');
const https = require('node:https');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PUERTO_UDP = 41234;
const PUERTO_HTTP = 3000;
const PUERTO_HTTPS = 3443;        // mando web: los navegadores solo dan los sensores por HTTPS
const MAX_JUGADORES = 2;
const TIEMPO_SIN_DATOS_MS = 3000;

// Cada sala recibe uno de estos nombres al azar.
const NOMBRES = [
  'Daniel', 'Arantxa', 'Vasco', 'Camila', 'Mateo',
  'Valeria', 'Diego', 'Lucía', 'Bruno', 'Renata',
  'Gonzalo', 'Micaela', 'Rodrigo', 'Ximena', 'Joaquín',
  'Isabel', 'Santiago', 'Paula', 'Emilio', 'Antonella',
];

let nombreSala = null;            // se elige al arrancar; «Otro nombre» lo cambia
let salaAbierta = false;          // la sala existe desde que la laptop pulsa «Crear sala»
let claveSala = '';               // contraseña de la sala ('' = sin contraseña)
let estadoLaptop = '0,0';         // «cámara activa, práctica en curso» (0 o 1), para los botones del celular
let urlMando = '';                // dirección que abre el celular (la del QR)
const salasVecinas = new Set();   // nombres ya usados por otras laptops del WiFi
// clave -> { id, clave, nombre, ultimo, paso, aviso } y además:
//   con la app:        { ip, puerto }     (clave = ip)
//   con el navegador:  { ws }             (clave = 'web:' + número)
const jugadores = new Map();
const navegadores = new Set();    // respuestas SSE abiertas

function emitir(texto) {
  for (const res of navegadores) res.write(`data: ${texto}\n\n`);
}

function idLibre() {
  const usados = new Set([...jugadores.values()].map((j) => j.id));
  for (let id = 1; id <= MAX_JUGADORES; id++) if (!usados.has(id)) return id;
  return null;
}

// Manda un texto al celular de ese jugador, por el camino que use.
function enviar(j, texto) {
  if (j.ws) enviarWs(j.ws, texto);
  else udp.send(texto, j.puerto, j.ip);
}

function quitarJugador(j) {
  if (jugadores.get(j.clave) !== j) return;
  jugadores.delete(j.clave);
  console.log(`Jugador ${j.id} desconectado`);
  emitir(`c,${j.id},0`);
}

// Atiende un texto llegado de un celular (app o navegador). `crear` devuelve los datos
// propios del camino para un jugador nuevo; `rechazar` avisa de que la sala está llena.
function recibir(clave, texto, crear, rechazar) {
  // Un jugador pulsó "Calibrar" en su celular.
  if (texto === 'cal') {
    const quien = jugadores.get(clave);
    if (quien) {
      console.log(`Jugador ${quien.id}: botón de calibrar`);
      emitir(`k,${quien.id}`);
    }
    return;
  }
  // Un jugador pulsó "Salir de la calibración".
  if (texto === 'calx') {
    const quien = jugadores.get(clave);
    if (quien) {
      quien.paso = 0;
      quien.aviso = 0;
      emitir(`x,${quien.id}`);
    }
    return;
  }
  // Órdenes del celular a la laptop: encender la cámara, empezar la práctica o volver a la sala.
  if (texto === 'cam' || texto === 'empezar' || texto === 'sala') {
    const quien = jugadores.get(clave);
    if (quien) emitir(`o,${quien.id},${texto}`);
    return;
  }
  // El jugador dice cómo se llama (solo el mando web por ahora).
  if (texto.startsWith('nombre,')) {
    const quien = jugadores.get(clave);
    if (quien) {
      quien.nombre = texto.slice(7).replace(/[,\n\r]/g, ' ').trim().slice(0, 12);
      emitir(`n,${quien.id},${quien.nombre}`);
    }
    return;
  }
  // Lo demás deben ser datos de sensores: números separados por comas.
  if (!salaAbierta || !/^-?\d/.test(texto)) return;

  let j = jugadores.get(clave);
  if (!j) {
    const id = idLibre();
    if (id === null) { rechazar(); return; }
    j = { id, clave, nombre: '', ultimo: 0, paso: 0, aviso: 0, ...crear() };
    jugadores.set(clave, j);
    console.log(`Jugador ${id} conectado (${j.ws ? 'navegador' : 'app'})`);
    emitir(`c,${id},1`);
  }
  j.ultimo = Date.now();
  emitir(`d,${j.id},${texto}`);
}

// Un nombre de la lista que no use otra laptop del WiFi ni sea el actual.
function nombreAlAzar() {
  let opciones = NOMBRES.filter((n) => !salasVecinas.has(n) && n !== nombreSala);
  if (!opciones.length) opciones = NOMBRES.filter((n) => n !== nombreSala);
  return opciones[Math.floor(Math.random() * opciones.length)];
}

function estadoSala() { return `a,${salaAbierta ? 1 : 0},${claveSala ? 1 : 0}`; }

function cerrarSala() {
  for (const j of [...jugadores.values()]) {
    if (j.ws) { enviarWs(j.ws, 'cerrada'); j.ws.destroy(); }
    quitarJugador(j);
  }
  salaAbierta = false;
  claveSala = '';
}

// Direcciones de difusión de cada red de la laptop (para avisar a todo el WiFi).
function direccionesDifusion() {
  const lista = new Set(['255.255.255.255']);
  for (const redes of Object.values(os.networkInterfaces())) {
    for (const red of redes) {
      if (red.family !== 'IPv4' || red.internal) continue;
      const ip = red.address.split('.').map(Number);
      const mascara = red.netmask.split('.').map(Number);
      lista.add(ip.map((n, i) => (n | (~mascara[i] & 255))).join('.'));
    }
  }
  return [...lista];
}

// Dirección de esta laptop en el WiFi: la de la red por la que sale a internet.
// (Conectar un UDP no manda nada; solo hace que el sistema elija la red.)
function ipLocal() {
  return new Promise((resolver) => {
    const primera = () => {
      for (const redes of Object.values(os.networkInterfaces())) {
        for (const red of redes) if (red.family === 'IPv4' && !red.internal) return red.address;
      }
      return 'localhost';
    };
    const s = dgram.createSocket('udp4');
    s.on('error', () => { s.close(); resolver(primera()); });
    s.connect(53, '8.8.8.8', () => {
      const ip = s.address().address;
      s.close();
      resolver(ip && ip !== '0.0.0.0' ? ip : primera());
    });
  });
}

// ---------- UDP: celulares con la app y descubrimiento de salas ----------
const udp = dgram.createSocket({ type: 'udp4', reuseAddr: false });

udp.on('message', (msg, rinfo) => {
  const texto = msg.toString('utf8');

  // Un celular (u otra laptop) pregunta qué salas hay.
  if (texto === 'buscar') {
    // Las salas con contraseña no se anuncian a la app: todavía no sabe pedirla.
    if (salaAbierta && !claveSala) udp.send(`sala,${nombreSala}`, rinfo.port, rinfo.address);
    return;
  }
  // Otra laptop anuncia su sala: solo interesa mientras elegimos nombre.
  if (texto.startsWith('sala,')) {
    if (!salaAbierta) salasVecinas.add(texto.slice(5));
    return;
  }
  if (claveSala) return;   // con contraseña solo entra el mando web
  recibir(rinfo.address, texto,
    () => ({ ip: rinfo.address, puerto: rinfo.port }),
    () => udp.send('llena', rinfo.port, rinfo.address));
  const j = jugadores.get(rinfo.address);
  if (j && !j.ws) j.puerto = rinfo.port;
});

udp.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error('Ya hay un servidor de Ping Phone abierto en esta laptop. Cierra el otro primero.');
  } else {
    console.error(e.message);
  }
  process.exit(1);
});

// Cada segundo: confirma a cada celular su número y descarta los que callaron.
setInterval(() => {
  const ahora = Date.now();
  for (const j of [...jugadores.values()]) {
    if (ahora - j.ultimo > TIEMPO_SIN_DATOS_MS) {
      if (j.ws) j.ws.destroy();
      quitarJugador(j);
    } else {
      // Incluye el paso de calibración por si el aviso suelto se perdió.
      enviar(j, `ok,${j.id},${j.paso},${j.aviso}`);
      if (j.ws) enviarWs(j.ws, `e,${estadoLaptop}`);   // la app no conoce este mensaje
    }
  }
}, 1000);

udp.bind(PUERTO_UDP, () => {
  udp.setBroadcast(true);
  // Pregunta qué salas existen ya en el WiFi para no repetir nombre.
  for (const d of direccionesDifusion()) udp.send('buscar', PUERTO_UDP, d, () => {});
  setTimeout(async () => {
    nombreSala = nombreAlAzar();
    servidor.listen(PUERTO_HTTP, () => {
      console.log(`Juego:  http://localhost:${PUERTO_HTTP}`);
    });
    if (servidorMando) {
      const ip = await ipLocal();
      servidorMando.listen(PUERTO_HTTPS, () => {
        urlMando = `https://${ip}:${PUERTO_HTTPS}/`;
        console.log(`Mando web (celular): ${urlMando}`);
        emitir(`u,${urlMando}`);
      });
    }
  }, 700);
});

// ---------- HTTP: página y eventos para el navegador de la laptop ----------
function archivo(res, nombre, tipo) {
  res.writeHead(200, { 'Content-Type': tipo, 'Cache-Control': 'no-store' });
  fs.createReadStream(path.join(__dirname, nombre)).pipe(res);
}

const servidor = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/') return archivo(res, 'index.html', 'text/html; charset=utf-8');
  if (url.pathname === '/qrcode.js') return archivo(res, 'qrcode.js', 'text/javascript; charset=utf-8');

  if (url.pathname === '/eventos') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    res.write(`data: s,${nombreSala}\n\n`);
    res.write(`data: ${estadoSala()}\n\n`);
    if (urlMando) res.write(`data: u,${urlMando}\n\n`);
    navegadores.add(res);
    for (const j of jugadores.values()) {
      res.write(`data: c,${j.id},1\n\n`);
      if (j.nombre) res.write(`data: n,${j.id},${j.nombre}\n\n`);
      // Una página recién abierta no está calibrando: el celular vuelve a su pantalla normal.
      j.paso = 0;
      j.aviso = 0;
      enviar(j, 'p,0,0');
    }
    req.on('close', () => navegadores.delete(res));
    return;
  }

  // Pantalla «Crear sala»: otro nombre al azar, abrir la sala (con o sin contraseña) y cerrarla.
  if (url.pathname === '/otro') {
    if (!salaAbierta) { nombreSala = nombreAlAzar(); emitir(`s,${nombreSala}`); }
    res.writeHead(204).end();
    return;
  }
  if (url.pathname === '/crear') {
    claveSala = (url.searchParams.get('clave') || '').trim().slice(0, 30);
    salaAbierta = true;
    console.log(`Sala abierta: ${nombreSala}${claveSala ? ' (con contraseña)' : ''}`);
    emitir(estadoSala());
    res.writeHead(204).end();
    return;
  }
  if (url.pathname === '/cerrar') {
    cerrarSala();
    console.log('Sala cerrada');
    emitir(estadoSala());
    res.writeHead(204).end();
    return;
  }

  // La página avisa si la cámara está activa y si la práctica empezó.
  if (url.pathname === '/estado') {
    estadoLaptop = /^[01],[01]$/.test(url.searchParams.get('e') || '') ? url.searchParams.get('e') : '0,0';
    for (const j of jugadores.values()) if (j.ws) enviarWs(j.ws, `e,${estadoLaptop}`);
    res.writeHead(204).end();
    return;
  }

  if (url.pathname === '/vibrar') {
    const id = Number(url.searchParams.get('j'));
    const ms = Math.min(Math.max(Number(url.searchParams.get('ms')) || 60, 10), 500);
    for (const j of jugadores.values()) {
      if (j.id === id) enviar(j, `v,${ms}`);
    }
    res.writeHead(204).end();
    return;
  }

  // La página avisa al celular en qué paso de la calibración está (0 = ninguno)
  // y si hay algún problema con la cámara (0 = ninguno, 1 = apagada, 2 = no ve a nadie, 3 = no ve la muñeca).
  if (url.pathname === '/paso') {
    const id = Number(url.searchParams.get('j'));
    const n = Number(url.searchParams.get('n')) || 0;
    const aviso = Number(url.searchParams.get('e')) || 0;
    for (const j of jugadores.values()) {
      if (j.id === id) {
        j.paso = n;
        j.aviso = aviso;
        enviar(j, `p,${n},${aviso}`);
      }
    }
    res.writeHead(204).end();
    return;
  }

  res.writeHead(404).end();
});

servidor.on('error', (e) => {
  console.error(e.code === 'EADDRINUSE' ? `El puerto ${PUERTO_HTTP} está ocupado por otro programa.` : e.message);
  process.exit(1);
});

// ---------- HTTPS: mando web para el celular ----------
// Dibujos de la calibración: se leen de index.html para no tener otra copia.
function dibujosCalibracion() {
  try {
    const pagina = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
    const m = pagina.match(/^const DIBUJO_PASO = \['',(.*)\];$/m);
    return m ? JSON.parse(`["",${m[1]}]`) : [];
  } catch (e) {
    return [];
  }
}

let servidorMando = null;
try {
  servidorMando = https.createServer({
    key: fs.readFileSync(path.join(__dirname, 'cert', 'clave.pem')),
    cert: fs.readFileSync(path.join(__dirname, 'cert', 'cert.pem')),
  }, (req, res) => {
    const url = new URL(req.url, 'https://localhost');
    if (url.pathname === '/') return archivo(res, 'mando.html', 'text/html; charset=utf-8');
    if (url.pathname === '/info') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ sala: nombreSala, abierta: salaAbierta, conClave: !!claveSala, dibujos: dibujosCalibracion() }));
      return;
    }
    // El celular comprueba la contraseña antes de pasar al mando.
    if (url.pathname === '/entrar') {
      const bien = salaAbierta && (url.searchParams.get('clave') || '') === claveSala;
      res.writeHead(bien ? 204 : 403).end();
      return;
    }
    res.writeHead(404).end();
  });
  servidorMando.on('upgrade', aceptarWs);
  servidorMando.on('error', (e) => {
    console.error(e.code === 'EADDRINUSE' ? `El puerto ${PUERTO_HTTPS} está ocupado: el mando web no está disponible.` : e.message);
  });
} catch (e) {
  console.log('Sin la carpeta cert/ no hay mando web (solo la app).');
}

// WebSocket mínimo (solo mensajes de texto cortos, que es lo único que se usa).
let siguienteWeb = 1;

function enviarWs(socket, texto) {
  if (socket.destroyed) return;
  const datos = Buffer.from(texto, 'utf8');
  const cabecera = datos.length < 126
    ? Buffer.from([0x81, datos.length])
    : Buffer.from([0x81, 126, datos.length >> 8, datos.length & 255]);
  socket.write(Buffer.concat([cabecera, datos]));
}

function aceptarWs(req, socket) {
  const llave = req.headers['sec-websocket-key'];
  if (req.url !== '/ws' || !llave) { socket.destroy(); return; }
  const acepta = crypto.createHash('sha1').update(llave + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
    + `Sec-WebSocket-Accept: ${acepta}\r\n\r\n`);
  socket.setNoDelay(true);

  const clave = 'web:' + siguienteWeb++;
  let autorizado = false;   // lo primero que manda el celular es «entrar,<contraseña>»
  let pendiente = Buffer.alloc(0);
  const cerrar = () => {
    const j = jugadores.get(clave);
    if (j) quitarJugador(j);
    socket.destroy();
  };

  socket.on('data', (trozo) => {
    pendiente = Buffer.concat([pendiente, trozo]);
    // Puede llegar más de un mensaje junto, o uno partido.
    while (pendiente.length >= 2) {
      const tipo = pendiente[0] & 0x0f;
      const conMascara = (pendiente[1] & 0x80) !== 0;
      let largo = pendiente[1] & 0x7f;
      let inicio = 2;
      if (largo === 126) {
        if (pendiente.length < 4) return;
        largo = pendiente.readUInt16BE(2);
        inicio = 4;
      } else if (largo === 127) { cerrar(); return; }   // mensajes enormes: no se usan
      const total = inicio + (conMascara ? 4 : 0) + largo;
      if (pendiente.length < total) return;
      let carga = pendiente.subarray(total - largo, total);
      if (conMascara) {
        const mascara = pendiente.subarray(inicio, inicio + 4);
        carga = Buffer.from(carga);
        for (let i = 0; i < carga.length; i++) carga[i] ^= mascara[i & 3];
      }
      pendiente = pendiente.subarray(total);

      if (tipo === 8) { cerrar(); return; }                                    // cierre
      if (tipo === 9) { socket.write(Buffer.concat([Buffer.from([0x8a, carga.length]), carga])); continue; } // ping
      if (tipo !== 1) continue;
      const texto = carga.toString('utf8');
      if (texto.startsWith('entrar,')) {
        autorizado = salaAbierta && texto.slice(7) === claveSala;
        if (!autorizado) enviarWs(socket, 'clave');
        continue;
      }
      if (!autorizado) continue;
      recibir(clave, texto, () => ({ ws: socket }), () => enviarWs(socket, 'llena'));
    }
  });
  socket.on('close', cerrar);
  socket.on('error', cerrar);
}
