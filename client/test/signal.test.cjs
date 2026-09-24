const {test}=require('node:test');
const assert=require('node:assert/strict');
const {SignalEngine}=require('../signal.cjs');
async function pair() {
  const a=await SignalEngine.create('alice'),b=await SignalEngine.create('bob');
  const bundle=await b.publicBundle(2); bundle.username='bob'; bundle.preKey=bundle.preKeys[0];
  await a.establish('bob',bundle); return {a,b,bundle};
}
function message(request,sender,recipient,id='1') { return {id,clientId:request.clientId,sender,recipient,ciphertext:request.ciphertext}; }
test('official libsignal encrypts bidirectionally and survives serialization',async()=>{
  const {a,b}=await pair();
  const first=await a.encrypt('bob','secret 中文 🔒');
  assert.ok(!first.ciphertext.includes('secret'));
  assert.equal((await b.decrypt(message(first,'alice','bob'))).body,'secret 中文 🔒');
  const resumed=new SignalEngine(JSON.parse(JSON.stringify(b.state)));
  const reply=await resumed.encrypt('alice','reply');
  assert.equal((await a.decrypt(message(reply,'bob','alice'))).body,'reply');
  const second=await a.encrypt('bob','ratchet');
  assert.equal(JSON.parse(second.ciphertext).type,2);
  assert.equal((await resumed.decrypt(message(second,'alice','bob','2'))).body,'ratchet');
});
test('tampering fails and original decrypt succeeds after transaction rollback',async()=>{
  const {a,b}=await pair(), sent=await a.encrypt('bob','untampered');
  const snapshot=structuredClone(b.state), envelope=JSON.parse(sent.ciphertext), raw=Buffer.from(envelope.data,'base64');
  raw[raw.length-2]^=1;envelope.data=raw.toString('base64');
  await assert.rejects(b.decrypt(message({...sent,ciphertext:JSON.stringify(envelope)},'alice','bob')));
  b.state=snapshot; assert.equal((await b.decrypt(message(sent,'alice','bob'))).body,'untampered');
});
test('duplicate deliveries use cache and routing cannot be substituted',async()=>{
  const {a,b}=await pair(),request=await a.encrypt('bob','once');
  await b.decrypt(message(request,'alice','bob'));
  assert.equal((await b.decrypt(message(request,'alice','bob'))).body,'once');
  await assert.rejects(b.decrypt(message(request,'alice','mallory')));
});
test('safety numbers match and identity replacement is rejected',async()=>{
  const {a,b}=await pair();
  const ap=await a.publicBundle(),bp=await b.publicBundle();
  assert.equal((await a.safety('bob',bp.identityKey)).code,(await b.safety('alice',ap.identityKey)).code);
  const other=await SignalEngine.create('bob'),newPublic=await other.publicBundle();
  await assert.rejects(a.safety('bob',newPublic.identityKey));
});
test('bad prekey signatures are rejected by libsignal',async()=>{
  const {bundle}=await pair(),fresh=await SignalEngine.create('charlie');
  bundle.signedPreKey.signature=Buffer.alloc(64).toString('base64');
  await assert.rejects(fresh.establish('bob',bundle));
});
