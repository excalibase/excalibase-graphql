const crypto = require('crypto');
const { stack } = require('./stack');

const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');

function sign(claims, secret = stack.hmacSecret, alg = 'HS256') {
  const header = encode({ alg, typ: 'JWT' });
  const payload = encode(claims);
  const signature = crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

function token({ role = 'user', project = stack.project, extra = {}, secret } = {}) {
  const claims = {
    sub: 'contract-user', projectId: project, aud: [`excalibase:${project}`], role,
    exp: Math.floor(Date.now() / 1000) + 3600, ...extra,
  };
  if (role === 'service') {
    claims.scope = 'service';
  }
  return sign(claims, secret);
}

const bearer = (jwt) => ({ Authorization: `Bearer ${jwt}` });

module.exports = { token, sign, bearer };
