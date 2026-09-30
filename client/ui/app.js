const $=id=>document.getElementById(id);
const F=window.ChatFeatures;let locationEpoch=0,live=null,watch=null,liveTimer=null,expiryTimer=null,searchTimer=null,searchPage=0,visibleEnd=null,historicalSnapshot=null,renderPeer='',messageNodes=new Map();
let state={accounts:[],servers:[],contacts:[],sessions:[],contactStates:[],messages:[]};
let indexedSnapshot=null,messageIndex=null;
let selected=null,activePeer='',tab='sessions',settingsOpen=false,safetyResult=null,safetyPeer='',context='',draftRevision=0;
const notice=message=>{$('notice').textContent=message||'';};
async function command(action,payload){const result=await window.chat.command(action,payload);if(!result.ok)throw new Error(result.error);return result.value;}
const accountKey=account=>account.server+'\0'+account.user;
const server=()=>state.selectedServer||state.server||(state.servers||[])[0]||'';
const accounts=()=>((state.accounts||[]).filter(account=>account.server===server()));
function resetPanels(){ $('moreMenu').hidden=true;$('more').setAttribute('aria-expanded','false');$('locationPanel').hidden=true;$('searchPanel').hidden=true;$('historyQuery').value='';$('historyFrom').value='';$('historyTo').value='';searchPage=0;clearTimeout(searchTimer);clearInterval(expiryTimer);expiryTimer=null;visibleEnd=null;historicalSnapshot=null;renderPeer='';messageNodes.clear();}
function clearConversation(){resetPanels();activePeer='';draftRevision++;safetyPeer='';safetyResult=null;$('body').value='';$('peer').value='';$('safetyPanel').hidden=true;calls.snapshot();}
function useAccount(account){selected=account;$('username').value=account.user;$('username').readOnly=true;$('register').hidden=true;$('usernameLabel').hidden=true;$('password').focus();renderAccountSelection();}
function useNewAccount(){selected=null;$('username').value='';$('username').readOnly=false;$('usernameLabel').hidden=false;$('register').hidden=false;renderAccountSelection();$('username').focus();}
function renderAccountSelection(){$('registrationWarning').hidden=$('register').hidden;for(const item of document.querySelectorAll('#accountList .account'))item.classList.toggle('active',!!selected&&item.dataset.key===accountKey(selected));}
function renderAccounts(){
  const list=$('accountList');list.replaceChildren();const filtered=accounts();$('accountPicker').hidden=!filtered.length;$('otherAccount').hidden=!filtered.length;
  for(const account of filtered){
    const item=document.createElement('li');item.className='account';item.dataset.key=accountKey(account);item.dataset.user=account.user;
    const pick=document.createElement('button');pick.type='button';pick.className='accountPick';pick.textContent=account.user;pick.onclick=()=>useAccount(account);
    const drop=document.createElement('button');drop.type='button';drop.className='accountForget';drop.textContent='移除';drop.setAttribute('aria-label','移除 '+account.user);drop.onclick=()=>forgetAccount(account);
    item.append(pick,drop);list.append(item);
  }
  if(selected&&!filtered.some(account=>accountKey(account)===accountKey(selected)))selected=null;
  if(!selected&&filtered.length&&$('username').value==='')useAccount(filtered[0]);
  else if(!selected){$('username').readOnly=false;$('usernameLabel').hidden=false;$('register').hidden=false;}
  renderAccountSelection();
}
function renderServers(){
  const list=$('serverList');list.replaceChildren();
  for(const address of state.servers||[]){const item=document.createElement('li');const pick=document.createElement('button');pick.type='button';pick.className='serverPick'+(address===server()?' active':'');pick.textContent=address;pick.onclick=()=>switchServer(address);item.append(pick);list.append(item);}
}
function peerMessages(peer){return messageIndex?.messages(peer)||[];}
function contact(peer){return (state.contactStates||[]).find(item=>item.username===peer);}
function peerStatus(peer){if(state.deletedPeers?.[peer])return '该用户已销户';if(state.identityChanges?.[peer])return '身份已更新，请重新核对';const item=contact(peer),presence=item?.online?'在线':'离线';if(item?.status==='pending_incoming')return presence+' · 等待你接受';if(item?.status==='pending_outgoing')return presence+' · 等待对方接受';if(item?.status==='accepted')return presence;return '尚未建立联系';}
function peers(){
  const names=new Set(tab==='contacts'?state.contacts||[]:state.sessions||[]);names.delete(state.username);names.delete('');
  const query=$('peerSearch').value.trim().toLocaleLowerCase();
  return [...names].map(name=>({name,messages:peerMessages(name)})).filter(item=>!query||item.name.toLocaleLowerCase().includes(query)||messageIndex.hasBodyMatch(item.name,query)).sort((a,b)=>{
    if(tab==='contacts')return a.name.localeCompare(b.name,'zh-CN');
    const at=a.messages.at(-1)?.createdAt||'',bt=b.messages.at(-1)?.createdAt||'';return bt.localeCompare(at)||a.name.localeCompare(b.name);
  });
}
function renderPeers(){
  $('sessionsTab').classList.toggle('active',tab==='sessions');$('contactsTab').classList.toggle('active',tab==='contacts');
  $('peerSearch').placeholder=tab==='sessions'?'搜索会话':'搜索联系人';
  const list=$('peerList');list.replaceChildren();
  for(const item of peers()){
    const row=document.createElement('li');const button=document.createElement('button');button.type='button';button.className='peerPick'+(item.name===activePeer?' active':'');button.dataset.peer=item.name;
    const avatar=document.createElement('span');avatar.className='avatar';avatar.textContent=item.name.slice(0,1).toUpperCase();
    const detail=document.createElement('span');detail.className='peerDetail';const name=document.createElement('strong');name.textContent=item.name;detail.append(name);
    const presence=document.createElement('small');presence.className='peerStatus'+(contact(item.name)?.online?' online':'');presence.textContent=peerStatus(item.name);detail.append(presence);
    const preview=document.createElement('small');preview.textContent=F.summary(item.messages.at(-1)?.body||'开始聊天');detail.append(preview);
    button.append(avatar,detail);button.onclick=()=>openPeer(item.name);row.append(button);list.append(row);
  }
  if(!list.children.length){const empty=document.createElement('li');empty.className='listEmpty';empty.textContent=$('peerSearch').value.trim()?'没有匹配结果':tab==='contacts'?'暂无联系人':'暂无会话';list.append(empty);}
}
function renderConversation(){
  $('emptyConversation').hidden=!!activePeer;$('conversationContent').hidden=!activePeer;if(!activePeer)return;
  $('conversationName').textContent=activePeer;
  $('conversationStatus').textContent=peerStatus(activePeer);
  $('conversationStatus').classList.toggle('online',!!contact(activePeer)?.online);
  const deleted=!!state.deletedPeers?.[activePeer],changed=state.identityChanges?.[activePeer];
  if(changed&&safetyResult&&safetyResult.publicKey!==changed.identityKey){safetyPeer='';safetyResult=null;$('safetyPanel').hidden=true;}
  if(deleted){safetyPeer='';safetyResult=null;$('safetyPanel').hidden=true;}
  $('verification').textContent=deleted?'旧身份与安全码已清除，聊天记录保留':(state.verifiedPeers||[]).includes(activePeer)?'安全码已核对':'安全码尚未核对';
  // A retired username may be registered again. Keep the safety-code action
  // available so the user can fetch and explicitly verify the new identity.
  $('safety').disabled=false;
  const relation=contact(activePeer)?.status;
  const canSend=state.online&&!deleted&&!changed&&(relation==='accepted'||!relation);
  $('send').disabled=!canSend;$('body').disabled=!canSend;
  $('body').placeholder=changed?'请先核对新的安全码':deleted?'该用户已销户，聊天记录仍保留':relation==='pending_incoming'?'接受聊天后可回复':relation==='pending_outgoing'?'等待对方接受后可继续发送':'发送消息…';
  $('acceptContact').hidden=relation!=='pending_incoming';$('removeContact').hidden=relation!=='accepted'||!(state.contacts||[]).includes(activePeer);
  $('sendPosition').disabled=$('startLive').disabled=!(state.online&&relation==='accepted'&&!deleted&&!changed)||!!live;
  if(live&&(!state.online||!state.username||state.identityChanges?.[live.peer]||state.deletedPeers?.[live.peer]||contact(live.peer)?.status!=='accepted'))stopLive();
  renderMessages();if(!$('searchPanel').hidden)renderSearch();

}
async function openPeer(peer){try{if(activePeer!==peer){resetPanels();draftRevision++;}activePeer=peer;if(!expiryTimer)expiryTimer=setInterval(refreshLocationCards,15000);safetyPeer='';safetyResult=null;$('safetyPanel').hidden=true;if(!(state.sessions||[]).includes(peer))render(await command('openConversation',{peer}));else{renderPeers();renderConversation();}calls.snapshot();$('body').focus();}catch(error){notice(error.message);}}
function render(next){
  const oldIdentity=context;if(live&&(!next.online||next.server!==state.server||next.username!==state.username))stopLive();state=next;const current=(state.username?state.server+'\0'+state.username:'')+'|'+server();
  if(indexedSnapshot!==next||messageIndex?.user!==state.username||messageIndex?.server!==server()){messageIndex=F.messageIndex(state.messages||[],state.username,server());indexedSnapshot=next;}
  if(oldIdentity&&oldIdentity!==current){clearConversation();selected=null;$('username').value='';$('password').value='';$('peerSearch').value='';}
  context=current;
  const hasServer=!!server();$('serverSetup').hidden=hasServer||settingsOpen;$('auth').hidden=!hasServer||!!state.username||settingsOpen;$('chat').hidden=!state.username||settingsOpen;$('settings').hidden=!settingsOpen;
  $('authServer').textContent=server();$('identity').textContent=state.username||'';$('connection').textContent=state.status||'';
  $('accountSettings').hidden=!state.username;$('settingsAccount').textContent=state.username?state.username+' · '+state.server:'';
  if(!state.username)renderAccounts();renderServers();renderPeers();renderConversation();calls.snapshot();if(state.error)notice(state.error);
}
async function forgetAccount(account){try{if(selected&&accountKey(selected)===accountKey(account)){selected=null;$('username').value='';}render(await command('forgetAccount',{server:account.server,user:account.user}));notice('已从列表移除 '+account.user+'；本机密钥和受保护的历史缓存仍保留。');}catch(error){notice(error.message);}}
async function saveServer(address){await stopLive();try{render(await command('saveServer',{server:address.trim()}));settingsOpen=false;render(state);notice('服务器已保存。');}catch(error){notice(error.message);}}
async function switchServer(address){await stopLive();if(address===server()){settingsOpen=false;render(state);return;}try{render(await command('selectServer',{server:address}));settingsOpen=false;render(state);notice('已切换服务器，请选择账号登录。');}catch(error){notice(error.message);}}
async function login(register){if(!$('authForm').reportValidity())return;const buttons=[...$('authForm').querySelectorAll('button')];buttons.forEach(button=>button.disabled=true);notice('正在连接并初始化加密设备…');try{
  const snapshot=await command('login',{server:server(),username:$('username').value.trim(),password:$('password').value,register});selected=(snapshot.accounts||[]).find(account=>account.server===snapshot.server&&account.user===snapshot.username)||null;render(snapshot);$('password').value='';notice('已登录。首次通信请核对联系人的安全码。');
}catch(error){notice(error.message);}finally{buttons.forEach(button=>button.disabled=false);}}
$('setupForm').onsubmit=event=>{event.preventDefault();saveServer($('server').value);};
$('addServerForm').onsubmit=event=>{event.preventDefault();saveServer($('newServer').value);$('newServer').value='';};
$('authForm').onsubmit=event=>{event.preventDefault();login(false);};$('register').onclick=()=>login(true);$('otherAccount').onclick=useNewAccount;
$('authSettings').onclick=$('chatSettings').onclick=()=>{settingsOpen=true;render(state);};
$('closeSettings').onclick=()=>{settingsOpen=false;render(state);};
$('logout').onclick=async()=>{try{await stopLive();await command('logout');render(await command('snapshot'));settingsOpen=false;render(state);notice('已退出，可选择另一个账号登录。');}catch(error){notice(error.message);}};
$('sessionsTab').onclick=()=>{tab='sessions';$('peerSearch').value='';renderPeers();};$('contactsTab').onclick=()=>{tab='contacts';$('peerSearch').value='';renderPeers();};$('peerSearch').oninput=renderPeers;
$('contactForm').onsubmit=async event=>{event.preventDefault();try{const peer=$('peer').value.trim();render(await command('addContact',{peer}));await openPeer(peer);$('peer').value='';notice('已打开聊天。发送首条消息后，需等待对方接受才能继续发送。');}catch(error){notice(error.message);}};
$('acceptContact').onclick=async()=>{try{render(await command('acceptContact',{peer:activePeer}));notice('已接受聊天，现在可以回复。');}catch(error){notice(error.message);}};
$('clearConversation').onclick=async()=>{try{const peer=activePeer;render(await command('clearConversation',{peer}));clearConversation();renderPeers();renderConversation();notice('会话已从列表清除，聊天记录仍保留；可从联系人重新打开。');}catch(error){notice(error.message);}};
$('removeContact').onclick=async()=>{try{render(await command('removeContact',{peer:activePeer}));notice('联系人关系已解除，聊天记录仍保留。之后的新消息需要重新接受。');}catch(error){notice(error.message);}};
$('body').oninput=()=>{draftRevision++;};
$('sendForm').onsubmit=async event=>{event.preventDefault();if(!activePeer)return;const peer=activePeer,body=$('body').value,revision=draftRevision;try{render(await command('send',{peer,body}));if(activePeer===peer&&draftRevision===revision)$('body').value='';notice('消息已在本机加密并加入待发队列。');}catch(error){notice(error.message);}};
$('history').onclick=async()=>{try{render(await command('history',{peer:activePeer}));notice('已同步服务器回执；历史正文来自本机受保护的缓存。');}catch(error){notice(error.message);}};
$('safety').onclick=async()=>{try{safetyPeer=activePeer;safetyResult=await command('safety',{peer:safetyPeer});$('safetyCode').textContent=safetyResult.code.match(/.{1,5}/g).join(' ');$('safetyPanel').hidden=false;$('verification').textContent=safetyResult.verified?'安全码已核对':'请通过可信渠道核对安全码';}catch(error){notice(error.message);}};
$('confirmSafety').onclick=async()=>{try{if(!safetyResult||safetyPeer!==activePeer)throw new Error('请重新获取安全码');safetyResult=await command('safety',{peer:safetyPeer,confirm:true,expectedCode:safetyResult.code});$('verification').textContent='安全码已核对';notice('核对结果已在本机保存，身份密钥变化将被阻止。');}catch(error){notice(error.message);}};
const calls=window.ChatCalls.create({command,notice,getState:()=>state,getPeer:()=>activePeer});
window.chat.onCall(event=>calls.onEvent(event));
window.chat.subscribe(render);
command('snapshot').then(render).catch(error=>notice(error.message));
function messageKey(m){return m.sender+':'+m.clientId;}
function refreshLocationCards(){if(!activePeer||document.hidden)return;const tracker=F.sessions(peerMessages(activePeer));for(const [key,node] of messageNodes){const item=tracker.get(key);if(item){node.querySelector('strong').textContent='📍 实时位置 · '+item.status;}}}
function renderMessages(jump){
 const all=peerMessages(activePeer),log=$('messages'),tracker=F.sessions(all),used=new Set(),display=[];
 if(jump){const packet=F.parse(jump.body);historicalSnapshot=packet&&packet.kind!=='pin'?messageKey(jump):null;}
 for(const m of all){const p=F.parse(m.body);if(p&&p.kind!=='pin'){const key=m.sender+'\0'+p.sessionId;if(used.has(key)){if(messageKey(m)===historicalSnapshot)display.push({m,key:'snapshot:'+messageKey(m),snapshot:true});continue;}used.add(key);display.push({m,key,session:tracker.get(key)});if(messageKey(m)===historicalSnapshot)display.push({m,key:'snapshot:'+messageKey(m),snapshot:true});}else display.push({m,key:messageKey(m)});}
 let jumpKey;if(jump){const p=F.parse(jump.body);jumpKey=p&&p.kind!=='pin'?'snapshot:'+messageKey(jump):messageKey(jump);const index=display.findIndex(item=>item.key===jumpKey);visibleEnd=Math.min(display.length,index+21);}
 const end=visibleEnd===null?display.length:Math.min(visibleEnd,display.length),items=display.slice(Math.max(0,end-100),end),wasBottom=log.scrollHeight-log.scrollTop-log.clientHeight<80;
 if(renderPeer!==activePeer){log.replaceChildren();messageNodes.clear();renderPeer=activePeer;}
 const keep=new Set(items.map(item=>item.key));for(const [key,node] of messageNodes)if(!keep.has(key)){node.remove();messageNodes.delete(key);}
 let cursor=log.firstChild;
 for(const item of items){let bubble=messageNodes.get(item.key);if(!bubble){bubble=document.createElement('div');bubble.className='bubble'+(item.m.sender===state.username?' mine':'');bubble.dataset.messageKey=messageKey(item.m);if(item.snapshot)bubble.dataset.historySnapshot='true';messageNodes.set(item.key,bubble);}const m=item.m,p=item.session?.value||F.parse(m.body),sig=JSON.stringify([m.body,m.status,item.session?.status,p,!!live,item.snapshot]);
 if(bubble.dataset.signature!==sig){bubble.replaceChildren();if(p){bubble.classList.add('locationCard');const title=document.createElement('strong');title.textContent=item.snapshot?'📍 历史位置快照 · '+(p.kind==='stop'?'当时已停止':'当时共享中'):p.kind==='pin'?'📍 位置':'📍 实时位置 · '+item.session.status;const coords=document.createElement('p');coords.textContent=p.latitude===undefined?'共享已结束':p.latitude.toFixed(6)+', '+p.longitude.toFixed(6);const detail=document.createElement('small');detail.textContent=(p.accuracy>0?'定位精度约 '+p.accuracy+' 米':'精度未提供')+' · '+new Date(p.recordedAt).toLocaleString();bubble.append(title,coords,detail);if(p.kind!=='pin'&&!item.snapshot){const own=document.createElement('button');own.type='button';own.className='textButton';own.textContent=live?'正在共享我的位置':'共享我的位置';own.disabled=!!live||contact(activePeer)?.status!=='accepted'||!state.online;own.onclick=startLive;bubble.append(own);}}else{const body=document.createElement('p');body.textContent=m.body;bubble.append(body);}const meta=document.createElement('div');meta.className='meta';meta.textContent=new Date(m.createdAt).toLocaleString()+' · '+m.status;bubble.append(meta);bubble.dataset.signature=sig;}
 if(bubble!==cursor)log.insertBefore(bubble,cursor);cursor=bubble.nextSibling;
 }
 $('olderMessages').hidden=end<=100;
 if(jumpKey){const node=messageNodes.get(jumpKey);node?.scrollIntoView({block:'center'});node?.classList.add('highlight');setTimeout(()=>node?.classList.remove('highlight'),1800);}else if(wasBottom&&visibleEnd===null)log.scrollTop=log.scrollHeight;
}
function renderSearch(){if(!activePeer)return;const from=$('historyFrom').value,to=$('historyTo').value;if(from&&to&&from>to){$('historyCount').textContent='开始日期不能晚于结束日期';$('historyResults').replaceChildren();$('historyPrev').disabled=$('historyNext').disabled=true;return;}const result=messageIndex.search(activePeer,{query:$('historyQuery').value,from,to,page:searchPage});$('historyCount').textContent=`共 ${result.total} 条 · 第 ${searchPage+1} 页`;$('historyPrev').disabled=searchPage===0;$('historyNext').disabled=(searchPage+1)*20>=result.total;$('historyResults').replaceChildren();for(const m of result.items){const row=document.createElement('li'),button=document.createElement('button');button.type='button';button.textContent=new Date(m.createdAt).toLocaleString()+' · '+F.summary(m.body);button.onclick=()=>renderMessages(m);row.append(button);$('historyResults').append(row);}}
function closeMenu(){$('moreMenu').hidden=true;$('more').setAttribute('aria-expanded','false');}
$('more').onclick=()=>{const open=$('moreMenu').hidden;$('moreMenu').hidden=!open;$('more').setAttribute('aria-expanded',String(open));};
$('locationMenu').onclick=()=>{closeMenu();if(live)$('locationStatus').textContent='正在与 '+live.peer+' 共享位置；停止后可向当前联系人共享。';$('locationPanel').hidden=false;$('searchPanel').hidden=true;if(historicalSnapshot){historicalSnapshot=null;visibleEnd=null;renderMessages();}};$('findHistory').onclick=()=>{closeMenu();$('searchPanel').hidden=false;$('locationPanel').hidden=true;searchPage=0;renderSearch();$('historyQuery').focus();};
$('closeLocation').onclick=()=>{$('locationPanel').hidden=true;};$('closeSearch').onclick=()=>{$('searchPanel').hidden=true;historicalSnapshot=null;visibleEnd=null;renderMessages();};
for(const id of ['historyQuery','historyFrom','historyTo'])$(id).oninput=()=>{clearTimeout(searchTimer);searchPage=0;searchTimer=setTimeout(renderSearch,200);};
$('historyPrev').onclick=()=>{searchPage--;renderSearch();};$('historyNext').onclick=()=>{searchPage++;renderSearch();};$('olderMessages').onclick=()=>{visibleEnd=Math.max(1,(visibleEnd??peerMessages(activePeer).length)-80);renderMessages();};
document.addEventListener('click',e=>{if(!e.target.closest('.moreWrap'))closeMenu();});document.addEventListener('keydown',e=>{if(e.key==='Escape')closeMenu();});
function locationPayload(kind,coords,sessionId=crypto.randomUUID(),seq=0,expiry=Date.now()+F.HOUR,recordedAt=Date.now()){const now=Date.now();expiry=Math.min(expiry,recordedAt+F.HOUR);return {v:1,kind,sessionId,seq,...(coords?{latitude:coords.latitude,longitude:coords.longitude,accuracy:coords.accuracy}:{}),recordedAt:new Date(recordedAt).toISOString(),expiresAt:new Date(Math.max(recordedAt,expiry)).toISOString()};}
function validateFix(pos){if(!Number.isFinite(pos.timestamp)||pos.timestamp<Date.now()-120000||pos.timestamp>Date.now()+300000)throw new Error('定位结果时间无效或已超过两分钟，请重新获取位置。');return pos.timestamp;}
function geoError(error){return error?.code===1?'定位权限被拒绝，请检查 Windows 位置权限或发送手动坐标。':error?.code===3?'定位超时，可重试或发送手动坐标。':'此设备无法获取系统位置，请使用手动坐标；实时共享需要可用定位服务。';}
function getPosition(peer=activePeer){return command('nativePosition',{peer});}
$('sendPosition').onclick=async()=>{const peer=activePeer,identity=context,epoch=locationEpoch;try{await command('locationPermission',{peer});$('locationStatus').textContent='正在获取系统位置…';const pos=await getPosition();validateFix(pos);if(epoch!==locationEpoch||peer!==activePeer||identity!==context||!state.online||document.hidden)throw new Error('聊天或账号已改变，请重新发送');render(await command('sendLocation',{peer,body:F.encode(locationPayload('pin',pos.coords,crypto.randomUUID(),0,pos.timestamp+F.HOUR,pos.timestamp))}));$('locationStatus').textContent='位置已加密发送。系统位置可能为近似位置。';}catch(e){$('locationStatus').textContent=e.code?geoError(e):e.message;}finally{if(!live)await command('revokeLocationPermission').catch(()=>{});}};
$('manualPosition').onsubmit=async e=>{e.preventDefault();try{const coords={latitude:Number($('latitude').value),longitude:Number($('longitude').value),accuracy:0};render(await command('sendLocation',{peer:activePeer,body:F.encode(locationPayload('pin',coords))}));$('locationStatus').textContent='已发送你手动选定的固定坐标；不是系统定位。';}catch(err){$('locationStatus').textContent=err.message;}};
async function startLive(){if(live)return;const peer=activePeer,identity=context,epoch=locationEpoch;try{await command('locationPermission',{peer,live:true});$('locationStatus').textContent='正在获取系统位置，成功后开始共享…';const pos=await getPosition();validateFix(pos);if(epoch!==locationEpoch||context!==identity||peer!==activePeer||!state.online||document.hidden)throw new Error('当前界面已改变，请重新开始');const candidate={peer,identity,sessionId:crypto.randomUUID(),seq:0,expiry:Math.min(Date.now(),pos.timestamp)+F.HOUR,last:0,busy:false};live=candidate;await sendLive(pos,candidate);if(live!==candidate)return;watch=setTimeout(()=>pollLive(candidate),10000);liveTimer=setTimeout(stopLive,Math.max(0,candidate.expiry-Date.now()));$('stopLive').hidden=false;$('startLive').disabled=true;$('locationStatus').textContent='正在与 '+peer+' 共享；仅限前台，最多 1 小时，每 10 秒最多一次。';}catch(e){$('locationStatus').textContent=e.code?geoError(e):e.message;await stopLive();}}
async function pollLive(item){if(live!==item)return;try{const pos=await getPosition(item.peer);if(live===item)await sendLive(pos,item);}catch(e){if(live===item){$('locationStatus').textContent=e.message;await stopLive();}}finally{if(live===item)watch=setTimeout(()=>pollLive(item),10000);}}
async function sendLive(pos,item){if(live!==item||item.busy||Date.now()-item.last<10000)return;if(!state.online||context!==item.identity||Date.now()>=item.expiry||document.hidden)return stopLive();item.busy=true;item.last=Date.now();try{validateFix(pos);render(await command('sendLocation',{peer:item.peer,body:F.encode(locationPayload('live',pos.coords,item.sessionId,item.seq++,item.expiry,pos.timestamp))}));}catch(e){$('locationStatus').textContent=e.message;stopLive();}finally{item.busy=false;}}
async function stopLive(){locationEpoch++;const old=live;live=null;if(watch!==null){clearTimeout(watch);watch=null;}clearTimeout(liveTimer);liveTimer=null;$('stopLive').hidden=true;$('startLive').disabled=!(state.online&&contact(activePeer)?.status==='accepted');await command('revokeLocationPermission').catch(()=>{});if(old)await command('stopLocations').catch(()=>{});}
$('startLive').onclick=startLive;$('stopLive').onclick=async()=>{await stopLive();$('locationStatus').textContent='实时位置共享已停止。';};window.chat.onLocationStop(()=>{stopLive();$('locationStatus').textContent='应用离开前台，实时位置共享已停止。';});document.addEventListener('visibilitychange',()=>{if(document.hidden)stopLive();});window.addEventListener('beforeunload',()=>{clearInterval(expiryTimer);stopLive();});
