/* Ephemeral WebRTC calls. SDP, ICE and media are never written to the message vault. */
(() => {
  const fallbackIce=[{urls:'stun:stun.l.google.com:19302'}];
  function create({command,notice,getState,getPeer}) {
    const $=id=>document.getElementById(id);
    let current=null,actionsOpen=false;
    function updateVideo(item) {
      if(!same(item))return;
      $('callVideos').classList.toggle('hasLocalVideo',!!item.stream);
      $('callVideos').classList.toggle('showRemote',!!(item.connectedAt&&item.remoteReady));
    }
    function minimize(minimized) {
      $('callPanel').classList.toggle('isMinimized',minimized);
      $('callPanel').setAttribute('aria-modal',String(!minimized));
      $('minimizeCall').hidden=minimized;
      $('restoreCall').hidden=!minimized;
      if(!minimized){const card=$('callCard');card.style.left=card.style.top=card.style.right=card.style.bottom='';}
    }
    function actions(open) {actionsOpen=open;$('callActions').hidden=!open;$('toggleCallActions').setAttribute('aria-expanded',String(open));}
    function permitted(peer) {const s=getState(),contact=(s.contactStates||[]).find(item=>item.username===peer);return !!(s.online&&s.username&&contact?.status==='accepted'&&!s.deletedPeers?.[peer]&&!s.identityChanges?.[peer]);}
    function updateButtons() {const allowed=permitted(getPeer())&&!current;$('toggleCallActions').disabled=!allowed;$('startAudioCall').disabled=$('startVideoCall').disabled=!allowed;if(!allowed)actions(false);}
    function view(item,status) {
      $('callPanel').hidden=false;$('callPanel').classList.toggle('modeAudio',item.mode==='audio');$('callPanel').classList.toggle('modeVideo',item.mode==='video');
      $('callPeer').textContent=item.peer;$('callStatus').textContent=status;
      $('callVideos').hidden=item.mode!=='video';$('callAudioAvatar').hidden=item.mode==='video';updateVideo(item);
      $('acceptCall').hidden=item.direction!=='incoming'||item.accepted;
      $('rejectCall').hidden=item.direction!=='incoming'||item.accepted;$('muteCall').hidden=!item.stream;
      $('hangupCall').hidden=item.direction==='incoming'&&!item.accepted;
      $('muteCall').textContent=item.muted?'取消静音':'静音';
    }
    async function iceServers() {try{const servers=await command('callIce');return Array.isArray(servers)&&servers.length?servers:fallbackIce;}catch{return fallbackIce;}}
    function same(item) {return current===item;}
    async function signal(item,action,payload) {
      if(!same(item))return;
      await command('sendCall',{peer:item.peer,callId:item.id,mode:item.mode,action,...(payload===undefined?{}:{payload})});
    }
    function close(reason='',sendAction='') {
      const item=current;if(!item)return;
      current=null;clearTimeout(item.timeout);clearTimeout(item.disconnectTimer);clearInterval(item.durationTimer);
      item.pc?.close();item.stream?.getTracks().forEach(track=>track.stop());
      $('localVideo').srcObject=null;$('remoteVideo').srcObject=null;$('remoteAudio').srcObject=null;
      minimize(false);$('callPanel').hidden=true;actions(false);updateButtons();
      command('revokeCallMediaPermission').catch(()=>{});
      if(sendAction)command('sendCall',{peer:item.peer,callId:item.id,mode:item.mode,action:sendAction}).catch(()=>command('cancelCall',{callId:item.id}).catch(()=>{}));
      else command('cancelCall',{callId:item.id}).catch(()=>{});
      if(reason)notice(reason);
    }
    function armTimeout(item) {clearTimeout(item.timeout);item.timeout=setTimeout(()=>{if(same(item))close('通话等待超时','hangup');},45000);}
    async function setup(item) {
      const servers=await iceServers();if(!same(item))return;
      const pc=new RTCPeerConnection({iceServers:servers});item.pc=pc;
      pc.onicecandidate=event=>{if(!event.candidate||!same(item))return;const payload=JSON.stringify(event.candidate.toJSON());if(!item.descriptionSent)item.pendingIce.push(payload);else signal(item,'ice',payload).catch(error=>close(error.message));};
      pc.ontrack=event=>{if(!same(item))return;const stream=event.streams[0]||new MediaStream([event.track]);$(item.mode==='video'?'remoteVideo':'remoteAudio').srcObject=stream;if(item.mode==='video'){item.remoteReady=stream.getVideoTracks().length>0;updateVideo(item);}};
      pc.onconnectionstatechange=()=>{
        if(!same(item))return;
        if(pc.connectionState==='connected'){
          clearTimeout(item.timeout);clearTimeout(item.disconnectTimer);
          if(!item.connectedAt)item.connectedAt=Date.now();updateVideo(item);
          clearInterval(item.durationTimer);
          const elapsed=()=>{if(same(item)){$('callStatus').textContent='通话中 · '+new Date(Date.now()-item.connectedAt).toISOString().slice(11,19);}};
          elapsed();item.durationTimer=setInterval(elapsed,1000);
        }
        if(pc.connectionState==='disconnected'){$('callStatus').textContent='连接中断，正在重连…';clearTimeout(item.disconnectTimer);item.disconnectTimer=setTimeout(()=>{if(same(item)&&pc.connectionState==='disconnected')close('通话连接已断开','hangup');},15000);}
        if(['failed','closed'].includes(pc.connectionState))close('通话连接已结束','hangup');
      };
      await command('callMediaPermission',{peer:item.peer,callId:item.id,mode:item.mode});
      if(!same(item))return;
      try{
        if(!navigator.mediaDevices?.getUserMedia)throw new Error('此设备无法使用麦克风或摄像头');
        item.stream=await navigator.mediaDevices.getUserMedia({audio:true,video:item.mode==='video'});
      }catch(error){
        if(error.name==='NotFoundError'||error.name==='DevicesNotFoundError')throw new Error(item.mode==='video'?'未找到可用的麦克风或摄像头':'未找到可用的麦克风');
        if(error.name==='NotAllowedError'||error.name==='PermissionDeniedError'||error.name==='SecurityError')throw new Error('麦克风或摄像头权限被拒绝，请检查 Windows 隐私设置');
        throw error;
      }finally{command('revokeCallMediaPermission').catch(()=>{});}
      if(!same(item)){item.stream?.getTracks().forEach(track=>track.stop());return;}
      for(const track of item.stream.getTracks())pc.addTrack(track,item.stream);
      if(item.mode==='video')$('localVideo').srcObject=item.stream;
      updateVideo(item);
      view(item,item.direction==='incoming'?'正在接通…':'正在呼叫…');
    }
    async function flushIce(item) {
      if(!item.pc?.remoteDescription)return;
      while(item.ice.length&&same(item))await item.pc.addIceCandidate(item.ice.shift());
    }
    async function flushLocalIce(item) {
      item.descriptionSent=true;
      while(item.pendingIce.length&&same(item))await signal(item,'ice',item.pendingIce.shift());
    }
    async function start(mode) {
      const peer=getPeer();if(current||!permitted(peer))return;
      actions(false);const item={peer,mode,id:crypto.randomUUID(),direction:'outgoing',accepted:true,ice:[],pendingIce:[],descriptionSent:false,pc:null,stream:null,timeout:null,disconnectTimer:null};
      current=item;minimize(false);view(item,'准备通话…');armTimeout(item);updateButtons();
      try{
        await command('beginCall',{peer,callId:item.id,mode});if(!same(item))return;
        await setup(item);if(!same(item))return;
        const offer=await item.pc.createOffer();await item.pc.setLocalDescription(offer);
        await signal(item,'offer',JSON.stringify(item.pc.localDescription));await flushLocalIce(item);
      }catch(error){if(same(item))close(error.message);}
    }
    async function accept() {
      const item=current;if(!item||item.direction!=='incoming'||item.accepted)return;
      item.accepted=true;view(item,'正在接听…');
      try{
        await command('approveCall',{callId:item.id});if(!same(item))return;
        await setup(item);if(!same(item))return;
        await item.pc.setRemoteDescription(JSON.parse(item.offer));await flushIce(item);
        const answer=await item.pc.createAnswer();await item.pc.setLocalDescription(answer);
        await signal(item,'answer',JSON.stringify(item.pc.localDescription));await flushLocalIce(item);
      }catch(error){if(same(item))close(error.message,'reject');}
    }
    async function onEvent(message) {
      if(message.type==='call_end'){if(current&&current.id===message.callId)close(message.reason);return;}
      if(message.type==='call_error'){if(current&&current.id===message.event?.callId)close(message.event?.error||'通话信令失败');return;}
      const event=message.event;if(message.type!=='call'||!event)return;
      if(event.action==='offer'){
        if(current?.id===event.callId&&current.peer===event.from&&current.mode===event.mode)return;
        if(current||!permitted(event.from)){command('sendCall',{peer:event.from,callId:event.callId,mode:event.mode,action:'busy'}).catch(()=>{});return;}
        if(typeof event.payload!=='string')return;
        const item={peer:event.from,mode:event.mode,id:event.callId,direction:'incoming',accepted:false,offer:event.payload,ice:[],pendingIce:[],descriptionSent:false,pc:null,stream:null,timeout:null,disconnectTimer:null};
        current=item;minimize(false);actions(false);view(item,'来电 · '+(item.mode==='video'?'视频':'语音'));armTimeout(item);updateButtons();return;
      }
      const item=current;if(!item||item.id!==event.callId||item.peer!==event.from||item.mode!==event.mode)return;
      if(['reject','busy','hangup'].includes(event.action)){close(event.action==='busy'?'对方正在通话':'对方已结束通话');return;}
      try{
        if(event.action==='ice'){
          if(typeof event.payload!=='string')return;
          item.remoteIce ||= new Set();
          if(item.remoteIce.has(event.payload))return;
          const candidate=JSON.parse(event.payload);if(item.remoteIce.size<512)item.remoteIce.add(event.payload);
          if(item.pc?.remoteDescription)await item.pc.addIceCandidate(candidate);else if(item.ice.length<256)item.ice.push(candidate);
        }else if(event.action==='answer'&&item.direction==='outgoing'&&typeof event.payload==='string'&&item.pc&&!item.answerReceived){
          item.answerReceived=true;
          await item.pc.setRemoteDescription(JSON.parse(event.payload));await flushIce(item);
        }
      }catch(error){if(same(item))close(error.message,'hangup');}
    }
    function snapshot() {const item=current;if(item&&(!permitted(item.peer)||!getState().online))close('通话条件已变化','hangup');updateButtons();}
    $('toggleCallActions').onclick=()=>actions(!actionsOpen);
    $('startAudioCall').onclick=()=>start('audio');$('startVideoCall').onclick=()=>start('video');
    $('acceptCall').onclick=accept;$('rejectCall').onclick=()=>close('已拒绝来电','reject');
    $('hangupCall').onclick=()=>close('通话已结束','hangup');
    $('minimizeCall').onclick=()=>minimize(true);
    $('restoreCall').onclick=()=>minimize(false);
    let drag=null;
    $('callHeading').addEventListener('pointerdown',event=>{
      if(!$('callPanel').classList.contains('isMinimized')||event.target.closest('button'))return;
      const card=$('callCard'),rect=card.getBoundingClientRect();
      drag={pointerId:event.pointerId,offsetX:event.clientX-rect.left,offsetY:event.clientY-rect.top};
      $('callHeading').setPointerCapture(event.pointerId);
    });
    $('callHeading').addEventListener('pointermove',event=>{
      if(!drag||drag.pointerId!==event.pointerId)return;
      const card=$('callCard');
      card.style.left=Math.max(0,Math.min(innerWidth-card.offsetWidth,event.clientX-drag.offsetX))+'px';
      card.style.top=Math.max(0,Math.min(innerHeight-card.offsetHeight,event.clientY-drag.offsetY))+'px';
      card.style.right=card.style.bottom='auto';
    });
    for(const name of ['pointerup','pointercancel'])$('callHeading').addEventListener(name,event=>{if(drag?.pointerId===event.pointerId)drag=null;});
    $('muteCall').onclick=()=>{const item=current;if(!item?.stream)return;const tracks=item.stream.getAudioTracks();item.muted=tracks.some(track=>track.enabled);tracks.forEach(track=>track.enabled=!item.muted);$('muteCall').textContent=item.muted?'取消静音':'静音';};
    window.addEventListener('beforeunload',()=>close('', 'hangup'));
    return {onEvent,snapshot,close};
  }
  window.ChatCalls={create};
})();
