const crypto = require('crypto');
const net = require('net');
const { stack } = require('./stack');

function maskedTextFrame(text) {
  const payload = Buffer.from(text);
  const mask = crypto.randomBytes(4);
  const header = Buffer.from([0x81, 0x80 | payload.length, ...mask]);
  const masked = Buffer.from(payload.map((byte, index) => byte ^ mask[index % 4]));
  return Buffer.concat([header, masked]);
}

/** Upgrade request and first frame in one TCP write: the first frame must not be lost behind the handshake. */
function upgradeWithPipelinedFrame(path, token, text) {
  const url = new URL(stack.app);
  const handshake = [
    `GET ${path} HTTP/1.1`, `Host: ${url.host}`, 'Upgrade: websocket', 'Connection: Upgrade',
    `Sec-WebSocket-Key: ${crypto.randomBytes(16).toString('base64')}`, 'Sec-WebSocket-Version: 13',
    'Sec-WebSocket-Protocol: graphql-transport-ws', `Authorization: Bearer ${token}`, '', '',
  ].join('\r\n');
  return new Promise((resolve, reject) => {
    const received = [];
    const socket = net.connect(Number(url.port), url.hostname, () => socket.write(Buffer.concat([Buffer.from(handshake), maskedTextFrame(text)])));
    socket.setTimeout(8000, () => { socket.destroy(); resolve(Buffer.concat(received).toString('latin1')); });
    socket.on('data', (chunk) => {
      received.push(chunk);
      if (Buffer.concat(received).toString('latin1').includes('connection_ack')) { socket.destroy(); resolve(Buffer.concat(received).toString('latin1')); }
    });
    socket.on('error', reject);
  });
}

module.exports = { upgradeWithPipelinedFrame };
