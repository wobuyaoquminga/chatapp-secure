'use strict';

// One operation per process keeps the fixture stateless: every call reloads
// the serialized Node engine, just as a restarted client would.
const { SignalEngine } = require('../../client/signal.cjs');

async function main() {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const request = JSON.parse(input);
  const engine = request.op === 'create'
    ? await SignalEngine.create(request.username)
    : new SignalEngine(request.state);
  let result;
  switch (request.op) {
    case 'create': result = null; break;
    case 'publicBundle': result = await engine.publicBundle(request.count || 0); break;
    case 'establish': result = await engine.establish(request.peer, request.bundle); break;
    case 'encrypt': result = await engine.encrypt(request.peer, request.body); break;
    case 'decrypt': result = await engine.decrypt(request.message); break;
    case 'accepted': result = engine.accepted(request.message); break;
    case 'safety': result = await engine.safety(request.peer, request.remotePublic); break;
    case 'hasSession': result = await engine.hasSession(request.peer); break;
    default: throw new Error(`Unknown operation: ${request.op}`);
  }
  process.stdout.write(JSON.stringify({ state: engine.state, result: result ?? null }));
}

main().catch(error => {
  process.stderr.write(String(error && error.stack || error));
  process.exitCode = 1;
});
