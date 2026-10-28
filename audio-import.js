/* Browser import fallback. Android uses a streaming native decoder and durable file queue. */
(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.VoiceAudioImport=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
    'use strict';
    const RATE=16000,MAX_UPLOAD=20*1024*1024;
    function check(signal){if(signal?.aborted){const error=new Error('Importazione interrotta');error.name='AbortError';throw error;}}
    function wav(parts,length){const header=new ArrayBuffer(44),view=new DataView(header);function text(at,value){for(let i=0;i<value.length;i++)view.setUint8(at+i,value.charCodeAt(i));}text(0,'RIFF');view.setUint32(4,length+36,true);text(8,'WAVEfmt ');view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);view.setUint32(24,RATE,true);view.setUint32(28,RATE*2,true);view.setUint16(32,2,true);view.setUint16(34,16,true);text(36,'data');view.setUint32(40,length,true);return new Blob([header,...parts],{type:'audio/wav'});}
    async function waveInfo(file){
        const first=new DataView(await file.slice(0,12).arrayBuffer()),tag=(v,at,n)=>Array.from({length:n},(_,i)=>String.fromCharCode(v.getUint8(at+i))).join('');
        if(first.byteLength<12||tag(first,0,4)!=='RIFF'||tag(first,8,4)!=='WAVE')return null;
        let pos=12,format=null,data=null;
        while(pos+8<=file.size){const head=new DataView(await file.slice(pos,pos+8).arrayBuffer()),kind=tag(head,0,4),size=head.getUint32(4,true);pos+=8;if(pos+size>file.size)throw new Error('File WAV incompleto');if(kind==='fmt '&&size>=16){const f=new DataView(await file.slice(pos,pos+Math.min(size,40)).arrayBuffer());format={encoding:f.getUint16(0,true),channels:f.getUint16(2,true),rate:f.getUint32(4,true),bits:f.getUint16(14,true)};}if(kind==='data')data={offset:pos,size};pos+=size+(size%2);}
        if(!format||!data)throw new Error('File WAV senza traccia audio');
        if(![1,3].includes(format.encoding)||![8,16,24,32].includes(format.bits)||format.encoding===3&&format.bits!==32)return null;
        if(format.channels<1||format.channels>8||format.rate<8000||format.rate>192000)throw new Error('Formato WAV non supportato');
        if(data.size%(format.channels*format.bits/8))throw new Error('Ultimo campione WAV incompleto');
        return {...format,...data};
    }
    class Encoder {
        constructor(rate,emit){this.rate=rate;this.emit=emit;this.frames=0;this.outputs=0;this.previous=0;this.total=0;this.parts=[];this.length=0;this.buffer=new Uint8Array(8192);this.used=0;this.quiet=0;this.peak=0;}
        sample(value){const index=this.frames++;while(this.outputs*this.rate<=index*RATE){const position=this.outputs*this.rate/RATE,fraction=index?Math.max(0,Math.min(1,position-(index-1))):1;this.output(index?this.previous+(value-this.previous)*fraction:value);this.outputs++;}this.previous=value;}
        output(value){const pcm=Math.round(Math.max(-1,Math.min(32767/32768,value))*32768);this.buffer[this.used++]=pcm&255;this.buffer[this.used++]=(pcm>>8)&255;if(this.used===this.buffer.length)this.flush();}
        flush(){if(!this.used)return;const block=this.buffer.slice(0,this.used);let squares=0;for(let i=0;i<block.length;i+=2){let x=block[i]|block[i+1]<<8;if(x&32768)x-=65536;squares+=x*x;}const rms=Math.sqrt(squares/(block.length/2));this.peak=Math.max(this.peak,rms);this.quiet=rms<=Math.max(100,this.peak*.12)?this.quiet+block.length:0;this.parts.push(block);this.length+=block.length;this.used=0;}
        async drain(force=false){if(!force&&this.length<RATE*2*35&&!(this.length>=RATE*2*30&&this.quiet>=RATE*2*.4))return;if(!this.length)return;const offsetMs=this.total*1000/(RATE*2),duration=this.length*1000/(RATE*2),blob=wav(this.parts,this.length);this.total+=this.length;this.length=0;this.parts=[];this.quiet=0;this.peak=0;await this.emit(blob,{offsetMs,endMs:offsetMs+duration});}
        async finish(){const wanted=Math.ceil(this.frames*RATE/this.rate);while(this.outputs<wanted){this.output(this.previous);this.outputs++;}this.flush();await this.drain(true);}
    }
    async function split(file,emit,{signal,onProgress=()=>{}}={}){
        if(!file.size)throw new Error('Il file audio è vuoto');check(signal);
        if(file.size<=MAX_UPLOAD){await emit(file,{offsetMs:0});onProgress(1);return;}
        const info=await waveInfo(file);
        if(info){
            const frame=info.channels*info.bits/8,blockSize=Math.floor(65536/frame)*frame,encoder=new Encoder(info.rate,emit);
            for(let pos=0;pos<info.size;pos+=blockSize){check(signal);const raw=await file.slice(info.offset+pos,info.offset+Math.min(info.size,pos+blockSize)).arrayBuffer(),view=new DataView(raw);
                for(let at=0;at<raw.byteLength;at+=frame){let value=0;for(let ch=0;ch<info.channels;ch++){const i=at+ch*info.bits/8;if(info.encoding===3)value+=view.getFloat32(i,true);else if(info.bits===8)value+=(view.getUint8(i)-128)/128;else if(info.bits===16)value+=view.getInt16(i,true)/32768;else if(info.bits===32)value+=view.getInt32(i,true)/2147483648;else {let n=view.getUint8(i)|(view.getUint8(i+1)<<8)|(view.getInt8(i+2)<<16);value+=n/8388608;}}encoder.sample(Number.isFinite(value)?value/info.channels:0);if(encoder.length>=RATE*2*35)await encoder.drain();}
                await encoder.drain();onProgress(Math.min(1,(pos+raw.byteLength)/info.size));}
            await encoder.finish();return;
        }
        if(file.size>128*1024*1024)throw new Error('Per questo file compresso usa l’app Android: importa in streaming senza caricare tutto in memoria.');
        const AudioCtx=rootAudioContext();if(!AudioCtx)throw new Error('Questo browser non può convertire l’audio. Usa l’app Android o un WAV PCM.');
        // Inspect the duration before allocating a decoded buffer for compressed sources.
        const url=URL.createObjectURL(file);let duration;
        try{duration=await new Promise((resolve,reject)=>{const audio=new Audio();const timer=setTimeout(()=>{audio.src='';reject(new Error('Durata audio non leggibile: usa l’app Android o un WAV PCM.'));},15000);const cleanup=()=>{clearTimeout(timer);audio.removeAttribute('src');audio.load();};audio.preload='metadata';audio.onloadedmetadata=()=>{const value=audio.duration;cleanup();resolve(value);};audio.onerror=()=>{cleanup();reject(new Error('Formato audio non supportato dal browser'));};audio.src=url;});}finally{URL.revokeObjectURL(url);}
        if(!Number.isFinite(duration)||duration>1800)throw new Error('Per audio compressi oltre 30 minuti usa l’app Android, che li elabora in streaming.');check(signal);
        const context=new AudioCtx();try{const decoded=await context.decodeAudioData(await file.arrayBuffer()),encoder=new Encoder(decoded.sampleRate,emit),channels=Array.from({length:decoded.numberOfChannels},(_,i)=>decoded.getChannelData(i));for(let start=0;start<decoded.length;start+=16384){check(signal);for(let i=start;i<Math.min(decoded.length,start+16384);i++){let value=0;for(const channel of channels)value+=channel[i];encoder.sample(value/channels.length);if(encoder.length>=RATE*2*35)await encoder.drain();}await encoder.drain();onProgress(Math.min(1,(start+16384)/decoded.length));await new Promise(resolve=>setTimeout(resolve,0));}await encoder.finish();}finally{await context.close();}
    }
    function rootAudioContext(){return typeof window!=='undefined'?(window.AudioContext||window.webkitAudioContext):null;}
    return {split,waveInfo,wav,Encoder,MAX_UPLOAD};
});
