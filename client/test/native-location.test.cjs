const {test}=require('node:test');const assert=require('node:assert/strict');const {EventEmitter}=require('node:events');const {NativeLocation,validatePosition,SCRIPT}=require('../native-location.cjs');
function fixture(options={}){let child,killed=0,args;const provider=new NativeLocation({timeoutMs:50,guardInterval:5,...options,spawnProcess:(...call)=>{args=call;child=new EventEmitter();child.stdout=new EventEmitter();child.kill=()=>{killed++;};return child;}});return {provider,get child(){return child;},get killed(){return killed;},get args(){return args;}};}
const fix=()=>({coords:{latitude:31.2,longitude:121.4,accuracy:25},timestamp:Date.now()});
test('native helper uses fixed Windows hidden command and validates fresh finite coordinates',async()=>{
 const f=fixture(),pending=f.provider.query(()=>true),value=fix();f.child.stdout.emit('data',Buffer.from(JSON.stringify(value)));f.child.emit('close',0);assert.deepEqual(await pending,value);assert.equal(f.args[2].windowsHide,true);assert.equal(f.args[2].stdio[2],'ignore');assert.equal(Buffer.from(f.args[1].at(-1),'base64').toString('utf16le'),SCRIPT);assert.equal(f.killed,1);
 for(const v of [{...value,timestamp:Date.now()-121000},{...value,timestamp:Date.now()+301000},{...value,coords:{...value.coords,latitude:91}},{...value,coords:{...value.coords,accuracy:NaN}}])assert.throws(()=>validatePosition(v));
});
test('native helper single-flight, timeout and cancel terminate helper without reporting location',async()=>{
 const f=fixture({timeoutMs:20}),pending=f.provider.query(()=>true);await assert.rejects(f.provider.query(()=>true),/稍候/);await assert.rejects(pending,/超时/);assert.equal(f.killed,1);assert.equal(f.provider.pending,null);
 const again=f.provider.query(()=>true);f.provider.cancel();await assert.rejects(again,/取消/);assert.equal(f.killed,2);
});
test('native helper rejects stale authorization both while waiting and at completion',async()=>{
 const f=fixture();let authorized=true;const pending=f.provider.query(()=>authorized);authorized=false;await assert.rejects(pending,/取消/);assert.equal(f.killed,1);
 authorized=true;const second=f.provider.query(()=>authorized);authorized=false;f.child.stdout.emit('data',JSON.stringify(fix()));f.child.emit('close',0);await assert.rejects(second,/取消/);assert.equal(f.killed,2);await assert.rejects(f.provider.query(()=>false),/权限/);
});
test('native provider denied/unavailable and malformed output become friendly failures',async()=>{
 for(const output of ['{"error":"denied"}','{"error":"unavailable"}','invalid']){const f=fixture(),pending=f.provider.query(()=>true);f.child.stdout.emit('data',output);f.child.emit('close',0);await assert.rejects(pending,/权限|不可用|无效/);}
});
