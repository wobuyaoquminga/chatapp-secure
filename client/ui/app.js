const $=id=>document.getElementById(id);
let state={accounts:[],servers:[],contacts:[],sessions:[],contactStates:[],messages:[]};
let selected=null,activePeer='',tab='sessions',settingsOpen=false,safetyResult=null,safetyPeer='',context='';
const notice=message=>{$('notice').textContent=message||'';};
async function command(action,payload){const result=await window.chat.command(action,payload);if(!result.ok)throw new Error(result.error);return result.value;}
const accountKey=account=>account.server+'\0'+account.user;
const server=()=>state.selectedServer||state.server||(state.servers||[])[0]||'';
const accounts=()=>((state.accounts||[]).filter(account=>account.server===server()));
function clearConversation(){activePeer='';safetyPeer='';safetyResult=null;$('body').value='';$('peer').value='';$('safetyPanel').hidden=true;}
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
function peerMessages(peer){return (state.messages||[]).filter(message=>(message.sender===state.username&&message.recipient===peer)||(message.sender===peer&&message.recipient===state.username)).sort((a,b)=>a.createdAt.localeCompare(b.createdAt));}
function contact(peer){return (state.contactStates||[]).find(item=>item.username===peer);}
function peerStatus(peer){if(state.deletedPeers?.[peer])return '该用户已销户';const item=contact(peer),presence=item?.online?'在线':'离线';if(item?.status==='pending_incoming')return presence+' · 等待你接受';if(item?.status==='pending_outgoing')return presence+' · 等待对方接受';if(item?.status==='accepted')return presence;return '尚未建立联系';}
function peers(){
  const names=new Set(tab==='contacts'?state.contacts||[]:state.sessions||[]);names.delete(state.username);names.delete('');
  const query=$('peerSearch').value.trim().toLocaleLowerCase();
  return [...names].map(name=>({name,messages:peerMessages(name)})).filter(item=>!query||item.name.toLocaleLowerCase().includes(query)||item.messages.some(message=>message.body.toLocaleLowerCase().includes(query))).sort((a,b)=>{
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
    const preview=document.createElement('small');preview.textContent=item.messages.at(-1)?.body||'开始聊天';detail.append(preview);
    button.append(avatar,detail);button.onclick=()=>openPeer(item.name);row.append(button);list.append(row);
  }
  if(!list.children.length){const empty=document.createElement('li');empty.className='listEmpty';empty.textContent=$('peerSearch').value.trim()?'没有匹配结果':tab==='contacts'?'暂无联系人':'暂无会话';list.append(empty);}
}
function renderConversation(){
  $('emptyConversation').hidden=!!activePeer;$('conversationContent').hidden=!activePeer;if(!activePeer)return;
  $('conversationName').textContent=activePeer;
  $('conversationStatus').textContent=peerStatus(activePeer);
  $('conversationStatus').classList.toggle('online',!!contact(activePeer)?.online);
  const deleted=!!state.deletedPeers?.[activePeer];
  if(deleted){safetyPeer='';safetyResult=null;$('safetyPanel').hidden=true;}
  $('verification').textContent=deleted?'旧身份与安全码已清除，聊天记录保留':(state.verifiedPeers||[]).includes(activePeer)?'安全码已核对':'安全码尚未核对';
  $('safety').disabled=deleted;
  const relation=contact(activePeer)?.status;
  const canSend=state.online&&!deleted&&(relation==='accepted'||!relation);
  $('send').disabled=!canSend;$('body').disabled=!canSend;
  $('body').placeholder=deleted?'该用户已销户，聊天记录仍保留':relation==='pending_incoming'?'接受聊天后可回复':relation==='pending_outgoing'?'等待对方接受后可继续发送':'发送消息…';
  $('acceptContact').hidden=relation!=='pending_incoming';$('removeContact').hidden=relation!=='accepted'||!(state.contacts||[]).includes(activePeer);
  const log=$('messages');log.replaceChildren();
  for(const message of peerMessages(activePeer)){
    const bubble=document.createElement('div');bubble.className='bubble'+(message.sender===state.username?' mine':'');
    const body=document.createElement('p');body.textContent=message.body;
    const meta=document.createElement('div');meta.className='meta';meta.textContent=new Date(message.createdAt).toLocaleString()+' · '+message.status;
    bubble.append(body,meta);log.append(bubble);
  }
  log.scrollTop=log.scrollHeight;
}
async function openPeer(peer){try{activePeer=peer;safetyPeer='';safetyResult=null;$('safetyPanel').hidden=true;if(!(state.sessions||[]).includes(peer))render(await command('openConversation',{peer}));else{renderPeers();renderConversation();}$('body').focus();}catch(error){notice(error.message);}}
function render(next){
  const oldIdentity=context;state=next;const current=(state.username?state.server+'\0'+state.username:'')+'|'+server();
  if(oldIdentity&&oldIdentity!==current){clearConversation();selected=null;$('username').value='';$('password').value='';$('peerSearch').value='';}
  context=current;
  const hasServer=!!server();$('serverSetup').hidden=hasServer||settingsOpen;$('auth').hidden=!hasServer||!!state.username||settingsOpen;$('chat').hidden=!state.username||settingsOpen;$('settings').hidden=!settingsOpen;
  $('authServer').textContent=server();$('identity').textContent=state.username||'';$('connection').textContent=state.status||'';
  $('accountSettings').hidden=!state.username;$('settingsAccount').textContent=state.username?state.username+' · '+state.server:'';
  if(!state.username)renderAccounts();renderServers();renderPeers();renderConversation();if(state.error)notice(state.error);
}
async function forgetAccount(account){try{if(selected&&accountKey(selected)===accountKey(account)){selected=null;$('username').value='';}render(await command('forgetAccount',{server:account.server,user:account.user}));notice('已从列表移除 '+account.user+'；本机密钥和受保护的历史缓存仍保留。');}catch(error){notice(error.message);}}
async function saveServer(address){try{render(await command('saveServer',{server:address.trim()}));settingsOpen=false;render(state);notice('服务器已保存。');}catch(error){notice(error.message);}}
async function switchServer(address){if(address===server()){settingsOpen=false;render(state);return;}try{render(await command('selectServer',{server:address}));settingsOpen=false;render(state);notice('已切换服务器，请选择账号登录。');}catch(error){notice(error.message);}}
async function login(register){if(!$('authForm').reportValidity())return;const buttons=[...$('authForm').querySelectorAll('button')];buttons.forEach(button=>button.disabled=true);notice('正在连接并初始化加密设备…');try{
  const snapshot=await command('login',{server:server(),username:$('username').value.trim(),password:$('password').value,register});selected=(snapshot.accounts||[]).find(account=>account.server===snapshot.server&&account.user===snapshot.username)||null;render(snapshot);$('password').value='';notice('已登录。首次通信请核对联系人的安全码。');
}catch(error){notice(error.message);}finally{buttons.forEach(button=>button.disabled=false);}}
$('setupForm').onsubmit=event=>{event.preventDefault();saveServer($('server').value);};
$('addServerForm').onsubmit=event=>{event.preventDefault();saveServer($('newServer').value);$('newServer').value='';};
$('authForm').onsubmit=event=>{event.preventDefault();login(false);};$('register').onclick=()=>login(true);$('otherAccount').onclick=useNewAccount;
$('authSettings').onclick=$('chatSettings').onclick=()=>{settingsOpen=true;render(state);};
$('closeSettings').onclick=()=>{settingsOpen=false;render(state);};
$('logout').onclick=async()=>{try{await command('logout');render(await command('snapshot'));settingsOpen=false;render(state);notice('已退出，可选择另一个账号登录。');}catch(error){notice(error.message);}};
$('sessionsTab').onclick=()=>{tab='sessions';$('peerSearch').value='';renderPeers();};$('contactsTab').onclick=()=>{tab='contacts';$('peerSearch').value='';renderPeers();};$('peerSearch').oninput=renderPeers;
$('contactForm').onsubmit=async event=>{event.preventDefault();try{const peer=$('peer').value.trim();render(await command('addContact',{peer}));await openPeer(peer);$('peer').value='';notice('已打开聊天。发送首条消息后，需等待对方接受才能继续发送。');}catch(error){notice(error.message);}};
$('acceptContact').onclick=async()=>{try{render(await command('acceptContact',{peer:activePeer}));notice('已接受聊天，现在可以回复。');}catch(error){notice(error.message);}};
$('clearConversation').onclick=async()=>{try{const peer=activePeer;render(await command('clearConversation',{peer}));clearConversation();renderPeers();renderConversation();notice('会话已从列表清除，聊天记录仍保留；可从联系人重新打开。');}catch(error){notice(error.message);}};
$('removeContact').onclick=async()=>{try{render(await command('removeContact',{peer:activePeer}));notice('联系人关系已解除，聊天记录仍保留。之后的新消息需要重新接受。');}catch(error){notice(error.message);}};
$('sendForm').onsubmit=async event=>{event.preventDefault();if(!activePeer)return;try{render(await command('send',{peer:activePeer,body:$('body').value}));$('body').value='';notice('消息已在本机加密并加入待发队列。');}catch(error){notice(error.message);}};
$('history').onclick=async()=>{try{render(await command('history',{peer:activePeer}));notice('已同步服务器回执；历史正文来自本机受保护的缓存。');}catch(error){notice(error.message);}};
$('safety').onclick=async()=>{try{safetyPeer=activePeer;safetyResult=await command('safety',{peer:safetyPeer});$('safetyCode').textContent=safetyResult.code.match(/.{1,5}/g).join(' ');$('safetyPanel').hidden=false;$('verification').textContent=safetyResult.verified?'安全码已核对':'请通过可信渠道核对安全码';}catch(error){notice(error.message);}};
$('confirmSafety').onclick=async()=>{try{if(!safetyResult||safetyPeer!==activePeer)throw new Error('请重新获取安全码');safetyResult=await command('safety',{peer:safetyPeer,confirm:true,expectedCode:safetyResult.code});$('verification').textContent='安全码已核对';notice('核对结果已在本机保存，身份密钥变化将被阻止。');}catch(error){notice(error.message);}};
window.chat.subscribe(render);
command('snapshot').then(render).catch(error=>notice(error.message));
