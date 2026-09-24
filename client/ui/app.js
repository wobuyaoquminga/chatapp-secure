const $=id=>document.getElementById(id);
let state={messages:[]},safetyResult,safetyPeer;
const notice=text=>{$('notice').textContent=text||'';};
async function command(action,payload){const r=await window.chat.command(action,payload);if(!r.ok)throw new Error(r.error);return r.value;}
function render(next){
  state=next;$('auth').hidden=!!state.username;$('chat').hidden=!state.username;
  $('identity').textContent=state.username||'';$('connection').textContent=state.status||'';$('send').disabled=!state.online;
  const peer=$('peer').value.trim();$('messages').replaceChildren();
  $('verification').textContent=(state.verifiedPeers||[]).includes(peer)?'已核对当前联系人的安全码':'尚未核对当前联系人的安全码';
  const messages=state.messages.filter(m=>(m.sender===state.username&&m.recipient===peer)||(m.sender===peer&&m.recipient===state.username));
  messages.sort((a,b)=>a.createdAt.localeCompare(b.createdAt));
  for(const m of messages){
    const card=document.createElement('div');card.className='bubble'+(m.sender===state.username?' mine':'');
    const meta=document.createElement('div');meta.className='meta';meta.textContent=m.sender+' · '+new Date(m.createdAt).toLocaleString();
    const body=document.createElement('p');body.textContent=m.body;
    const status=document.createElement('div');status.className='meta';status.textContent=m.status;
    card.append(meta,body,status);$('messages').append(card);
  }
  $('messages').scrollTop=$('messages').scrollHeight;if(state.error)notice(state.error);
}
window.chat.subscribe(render);
async function login(register){
  if(!$('authForm').reportValidity())return;
  const buttons=[...$('authForm').querySelectorAll('button')];buttons.forEach(b=>b.disabled=true);notice('连接服务器并初始化加密设备…');
  try{render(await command('login',{server:$('server').value.trim(),username:$('username').value,password:$('password').value,register}));$('password').value='';notice('已登录。请先核对联系人的安全码。');}
  catch(e){notice(e.message);}finally{buttons.forEach(b=>b.disabled=false);}
}
$('authForm').onsubmit=e=>{e.preventDefault();login(false)};$('register').onclick=()=>login(true);
$('logout').onclick=async()=>{await command('logout');$('peer').value='';$('body').value='';$('safetyPanel').hidden=true;notice('已退出，本地密钥仍由 Windows 保护。');};
$('peer').oninput=()=>{safetyResult=null;safetyPeer=null;$('safetyPanel').hidden=true;$('verification').textContent='尚未核对当前联系人的安全码';render(state);};
$('sendForm').onsubmit=async e=>{e.preventDefault();try{render(await command('send',{peer:$('peer').value.trim(),body:$('body').value}));$('body').value='';notice('已在本机加密，等待服务器确认。');}catch(error){notice(error.message);}};
$('history').onclick=async()=>{try{render(await command('history',{peer:$('peer').value.trim()}));notice('已同步服务器回执；历史正文来自本机受保护的缓存。');}catch(e){notice(e.message);}};
$('safety').onclick=async()=>{try{
  safetyPeer=$('peer').value.trim();safetyResult=await command('safety',{peer:safetyPeer});
  $('safetyCode').textContent=safetyResult.code.match(/.{1,5}/g).join(' ');$('safetyPanel').hidden=false;
  $('verification').textContent=safetyResult.verified?'已核对安全码':'首次使用信任：尚未通过可信渠道核对';
}catch(e){notice(e.message);}};
$('confirmSafety').onclick=async()=>{try{
  if(!safetyResult||safetyPeer!==$('peer').value.trim())throw new Error('请重新获取安全码');
  safetyResult=await command('safety',{peer:safetyPeer,confirm:true,expectedCode:safetyResult.code});$('verification').textContent='已核对安全码';notice('已在本机保存核对结果，身份密钥变化将被阻止。');
}catch(e){notice(e.message);}};
command('snapshot').then(render).catch(e=>notice(e.message));
