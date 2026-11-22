// The ports of the three test engines, from one place so a developer can move them off whatever else runs on the
// machine: E2E_PORT_BASE moves the engines (default 10000), E2E_MGMT_BASE the management ports (default 18081) and
// STATIC_PORT the server that holds the read-only engine's document (default 9099).
const base = Number(process.env.E2E_PORT_BASE || 10000);
const management = Number(process.env.E2E_MGMT_BASE || 18081);
const staticPort = Number(process.env.STATIC_PORT || 9099);

const ports = {
  writable: { port: base, admin: base + 1, management },
  readOnly: { port: base + 10, admin: base + 11, management: management + 1 },
  next: { port: base + 20, admin: base + 21, management: management + 2 },
  staticPort
};

module.exports = {
  ...ports,
  WRITABLE: `http://localhost:${ports.writable.admin}`,
  READ_ONLY: `http://localhost:${ports.readOnly.admin}`,
  NEXT: `http://localhost:${ports.next.admin}`
};
