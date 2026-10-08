'use strict';

// A getUserMedia grant belongs to one foreground call setup and one server generation.
// It is never persisted and expires even if the renderer forgets to revoke it.
class MediaLease {
  static trustedFrame({entryUrl,requestingUrl,isMainFrame}) {
    return isMainFrame===true&&typeof requestingUrl==='string'&&requestingUrl.toLowerCase()===entryUrl;
  }
  constructor(now=Date.now) {this.now=now;this.revoke();}
  grant({server,generation,callId,mode}) {
    this.value={server,generation,callId,mode,until:this.now()+20000};
  }
  revoke() {this.value=null;}
  allows({entryUrl,requestingUrl,isMainFrame,mediaTypes,server,generation,call}) {
    const lease=this.value;
    if(!lease||this.now()>=lease.until||!call?.userApproved||lease.server!==server||lease.generation!==generation||lease.callId!==call.callId||lease.mode!==call.mode)return false;
    if(!MediaLease.trustedFrame({entryUrl,requestingUrl,isMainFrame}))return false;
    if(!Array.isArray(mediaTypes)||!mediaTypes.length||mediaTypes.some(type=>!['audio','video'].includes(type)))return false;
    return lease.mode==='video'||mediaTypes.every(type=>type==='audio');
  }
}
module.exports={MediaLease};
