const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const WebSocket=require('ws');
const {once}=require('node:events');
const {Controller}=require('../controller.cjs');

const storage={isEncryptionAvailable:()=>true,encryptString:text=>Buffer.from(text),decryptString:data=>data.toString()};
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function waitFor(check,description) {
  const deadline=Date.now()+2000;
  while(!check()){
    if(Date.now()>deadline)assert.fail('Timed out waiting for '+description);
    await pause(5);
  }
}
async function fixture(serverOptions={}) {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-connection-'));
  const server=new WebSocket.Server({host:'127.0.0.1',port:0,...serverOptions});
  await once(server,'listening');
  const controller=new Controller(dir,storage,()=>{},
    {readyTimeoutMs:60,heartbeatIntervalMs:25,pongTimeoutMs:15,retryBaseMs:20,retryMaxMs:60,random:()=>1});
  controller.server='http://127.0.0.1:'+server.address().port;
  controller.user='alice';controller.token='test-token';
  controller.engine={state:{messages:{},outbox:{},verified:{},hiddenSessions:{},hiddenContacts:{}},accepted:()=>{}};
  controller.request=async endpoint=>{
    if(endpoint==='/api/account-events'||endpoint==='/api/contacts')return [];
    throw new Error('Unexpected request: '+endpoint);
  };
  return {server,controller,async close(){controller.logout();for(const socket of server.clients)socket.terminate();await new Promise(resolve=>server.close(resolve));fs.rmSync(dir,{recursive:true,force:true});}};
}

test('a dropped local WebSocket reconnects and successful ready sync resets backoff',async()=>{
  const f=await fixture();let connections=0;let first;
  try{
    f.server.on('connection',socket=>{
      connections++;if(!first)first=socket;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'first ready sync');
    first.terminate();
    await waitFor(()=>connections>=2&&f.controller.online,'reconnected ready sync');
    assert.equal(f.controller.retryAttempt,0);
  }finally{await f.close();}
});

test('missing ready frame times out; logout cancels retry and connection timers',async()=>{
  const f=await fixture();let connections=0;
  try{
    f.server.on('connection',()=>{connections++;});
    f.controller.connect();
    await waitFor(()=>connections>=2,'ready timeout reconnect');
    assert.ok(f.controller.retryAttempt>=1);
    f.controller.logout();
    const afterLogout=connections;
    await pause(180);
    assert.equal(connections,afterLogout);
    assert.equal(f.controller.retry,null);
    assert.equal(f.controller.readyTimeout,null);
    assert.equal(f.controller.heartbeat,null);
    assert.equal(f.controller.pongTimeout,null);
  }finally{await f.close();}
});

test('receiving ready keeps a slow account sync from triggering the ready timeout',async()=>{
  const f=await fixture();let connections=0;
  try{
    f.controller.request=async endpoint=>{
      if(endpoint==='/api/account-events'){await pause(130);return [];}
      if(endpoint==='/api/contacts')return [];
      throw new Error('Unexpected request: '+endpoint);
    };
    f.server.on('connection',socket=>{
      connections++;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'slow ready sync');
    assert.equal(connections,1);
    assert.equal(f.controller.retryAttempt,0);
  }finally{await f.close();}
});

test('an unresponsive local WebSocket is terminated after an unanswered ping',async()=>{
  const f=await fixture({autoPong:false});let connections=0,pings=0;
  try{
    f.server.on('connection',socket=>{
      connections++;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
      socket.on('ping',()=>{pings++;});
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'ready sync');
    await waitFor(()=>pings>=1&&connections>=2,'heartbeat reconnect');
  }finally{await f.close();}
});

test('vault write failure during a live event pauses the connection without retrying',async()=>{
  const f=await fixture();let connections=0;let socket;
  try{
    f.server.on('connection',peer=>{
      connections++;socket=peer;
      peer.on('message',()=>peer.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'ready sync');
    f.controller.vault={write:()=>{throw new Error('disk failure');}};
    socket.send(JSON.stringify({type:'accepted',message:{id:'one'}}));
    await waitFor(()=>f.controller.storageFailed&&f.controller.socket===null,'connection paused after storage failure');
    assert.match(f.controller.status,/本地保存失败.*已暂停连接/);
    await pause(140);
    assert.equal(connections,1);
    assert.equal(f.controller.retry,null);
  }finally{await f.close();}
});

test('ready sync authorization failure stops reconnecting and asks for login',async()=>{
  const f=await fixture();let connections=0;
  try{
    f.controller.request=async()=>{const error=new Error('expired token');error.status=401;throw error;};
    f.server.on('connection',socket=>{
      connections++;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.authFailed&&f.controller.socket===null,'authorization failure');
    assert.match(f.controller.status,/认证已失效.*重新登录/);
    await pause(140);
    assert.equal(connections,1);
    assert.equal(f.controller.retry,null);
  }finally{await f.close();}
});

test('network restoration bypasses backoff without creating duplicate sockets',async()=>{
  const f=await fixture();let connections=0,first;
  try{
    f.controller.connectionOptions.retryBaseMs=1000;
    f.controller.connectionOptions.retryMaxMs=1000;
    f.server.on('connection',socket=>{
      connections++;if(!first)first=socket;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();await waitFor(()=>f.controller.online,'first ready');
    first.terminate();await waitFor(()=>!!f.controller.retry,'scheduled retry');
    f.controller.networkRestored();f.controller.networkRestored();
    await waitFor(()=>connections===2&&f.controller.online,'immediate reconnect');
    await pause(100);assert.equal(connections,2);
  }finally{await f.close();}
});

test('failed message retry reuses its persisted envelope and is not replayed on reconnect',async()=>{
  const f=await fixture();const id='11111111-1111-4111-8111-111111111111';
  const envelope={type:'send',clientId:id,to:'bob',ciphertext:'encrypted-once',toAccountId:'bob-account'};
  const received=[];let socket;
  try{
    f.controller.vault={write:()=>{}};
    f.controller.engine.state.outbox[id]=envelope;
    f.controller.engine.state.messages['alice:'+id]={sender:'alice',recipient:'bob',clientId:id,ciphertext:'encrypted-once',body:'hello',status:'待发送'};
    f.server.on('connection',peer=>{
      socket=peer;peer.on('message',raw=>{
        const frame=JSON.parse(raw);if(frame.type==='auth')peer.send(JSON.stringify({type:'ready'}));
        if(frame.type==='send')received.push(frame);
      });
    });
    f.controller.connect();await waitFor(()=>received.length===1,'initial encrypted send');
    socket.send(JSON.stringify({type:'error',clientId:id,error:'服务暂时不可用'}));
    await waitFor(()=>f.controller.engine.state.messages['alice:'+id].status.startsWith('发送失败'),'failure state');
    socket.terminate();await waitFor(()=>f.controller.online&&f.controller.retryAttempt===0&&f.controller.generation>=2,'ready after reconnect');
    assert.equal(received.length,1,'failed envelope requires explicit retry');
    await f.controller.retryMessage({clientId:id});
    await waitFor(()=>received.length===2,'explicit retry');
    assert.deepEqual(received[1],received[0]);
    assert.match(f.controller.engine.state.messages['alice:'+id].status,/发送中/);
    await assert.rejects(f.controller.retryMessage({clientId:id}),/正在发送/);
    f.controller.engine.accepted=record=>{
      const message=f.controller.engine.state.messages['alice:'+record.clientId];
      message.id=record.id;message.status='服务器已保存密文';
      delete f.controller.engine.state.outbox[record.clientId];
    };
    socket.send(JSON.stringify({type:'accepted',message:{...envelope,id:'saved-once'}}));
    await waitFor(()=>!f.controller.engine.state.outbox[id],'persisted server acceptance');
    socket.terminate();await waitFor(()=>f.controller.online&&f.controller.generation>=3,'ready after acceptance');
    assert.equal(received.length,2,'accepted message is not replayed');
  }finally{await f.close();}
});

test('account deadline is saved for the same account and an older server may omit the endpoint',async()=>{
  const f=await fixture();
  try{
    f.controller.accountId='account-one';f.controller.vault={write:()=>{}};
    const status={serverTime:'2026-10-03T00:00:00Z',lastConnectedAt:'2026-10-03T00:00:00Z',accountExpiresAt:'2026-10-10T00:00:00Z',retentionDays:7};
    f.controller.request=async()=>status;
    await f.controller.refreshAccountStatus();
    assert.equal(f.controller.snapshot().accountStatus.accountId,'account-one');
    f.controller.request=async()=>{throw Object.assign(new Error('not found'),{status:404});};
    await f.controller.refreshAccountStatus();
    assert.equal(f.controller.snapshot().accountStatus.accountExpiresAt,status.accountExpiresAt);
    f.controller.accountId='account-two';assert.equal(f.controller.snapshot().accountStatus,null);
  }finally{await f.close();}
});

test('missing server acknowledgement becomes a manual retry of the same ciphertext',async()=>{
  const f=await fixture(),id='22222222-2222-4222-8222-222222222222',frames=[];
  const envelope={type:'send',clientId:id,to:'bob',ciphertext:'unchanged-encrypted-message'};
  try{
    f.controller.connectionOptions.ackTimeoutMs=35;f.controller.vault={write:()=>{}};
    f.controller.engine.state.outbox[id]=envelope;
    f.controller.engine.state.messages['alice:'+id]={sender:'alice',recipient:'bob',clientId:id,body:'hello',ciphertext:envelope.ciphertext,status:'待发送'};
    f.server.on('connection',socket=>socket.on('message',raw=>{
      const frame=JSON.parse(raw);if(frame.type==='auth')socket.send(JSON.stringify({type:'ready'}));
      if(frame.type==='send')frames.push(frame);
    }));
    f.controller.connect();await waitFor(()=>frames.length===1,'first send');
    await waitFor(()=>f.controller.engine.state.messages['alice:'+id].status==='发送失败 · 服务器确认超时','ack timeout');
    assert.equal(frames.length,1,'timeout does not auto resend');
    await f.controller.retryMessage({clientId:id});await waitFor(()=>frames.length===2,'manual retry');
    assert.deepEqual(frames[1],frames[0]);
  }finally{await f.close();}
});
