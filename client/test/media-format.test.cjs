'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const Media=require('../media-format.cjs'),Format=require('../ui/file-format.js');
test('media extensions map to formats only when the native signature matches',()=>{
 const samples=[
  ['a.jpg','image/jpeg',Buffer.from('ffd8ffe000','hex')],
  ['a.jpeg','image/jpeg',Buffer.from('ffd8ffe000','hex')],
  ['a.png','image/png',Buffer.from('89504e470d0a1a0a','hex')],
  ['a.gif','image/gif',Buffer.from('GIF89a')],
  ['a.webp','image/webp',Buffer.from('RIFF\0\0\0\0WEBPVP8 ')],
  ['a.mp4','video/mp4',Buffer.from('000000106674797069736f6d00000000','hex')],
  ['a.webm','video/webm',Buffer.concat([Buffer.from('1a45dfa3','hex'),Buffer.from('xxxxwebmxxxxxxxx')])]
 ];
 for(const [name,mime,bytes] of samples){assert.equal(Media.inspect(name,bytes).mime,mime);assert.equal(Format.mediaKind(name),mime.split('/')[0]);assert.throws(()=>Media.inspect(name,Buffer.from('<svg><script>bad</script></svg>')),/不符/);}
 assert.equal(Format.mediaKind('report.pdf'),null);
 assert.throws(()=>Media.inspect('a.svg',samples[2][2]),/不符/);
 assert.throws(()=>Media.inspect('a.mp4',samples[2][2]),/不符/);
});
