'use strict';
const Format=require('./ui/file-format.js');
const signatures={
  jpg:{mime:'image/jpeg',test:b=>b.length>=3&&b[0]===0xff&&b[1]===0xd8&&b[2]===0xff},
  jpeg:{mime:'image/jpeg',test:b=>b.length>=3&&b[0]===0xff&&b[1]===0xd8&&b[2]===0xff},
  png:{mime:'image/png',test:b=>b.length>=8&&b.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex'))},
  gif:{mime:'image/gif',test:b=>b.length>=6&&['GIF87a','GIF89a'].includes(b.toString('ascii',0,6))},
  webp:{mime:'image/webp',test:b=>b.length>=16&&b.toString('ascii',0,4)==='RIFF'&&b.toString('ascii',8,12)==='WEBP'},
  mp4:{mime:'video/mp4',test:b=>b.length>=16&&b.readUInt32BE(0)>=16&&b.readUInt32BE(0)<=b.length&&b.toString('ascii',4,8)==='ftyp'},
  webm:{mime:'video/webm',test:b=>b.length>=16&&b.subarray(0,4).equals(Buffer.from('1a45dfa3','hex'))&&b.subarray(0,64).includes(Buffer.from('webm'))}
};
function inspect(name,bytes){const extension=String(name).toLowerCase().split('.').pop(),entry=signatures[extension];if(!entry||!entry.test(bytes))throw Error('媒体格式与文件内容不符，请保存后检查');return {kind:Format.mediaKind(name),mime:entry.mime};}
module.exports={inspect};
