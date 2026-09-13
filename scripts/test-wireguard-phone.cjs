// Node 18+: node scripts/test-wireguard-phone.cjs
// Shipped page handlers with DOM adapters; not a browser/device test.
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict'), path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/java/com/streamvault/app/vpn/WireGuardPairingPage.kt'),'utf8');
const script=source.split('<script>')[1].split('</script>')[0];
const fields=Object.fromEntries(['form','file','config','name','send','result'].map(id=>[id,{
 value:'',files:[],textContent:'',hidden:false,disabled:false,handlers:{},
 addEventListener(event,handler){this.handlers[event]=handler;}
}]));
const requests=[];let ok=true;
vm.runInNewContext(script,{
 document:{getElementById:id=>fields[id]},Blob,URLSearchParams,
 FormData:class{*[Symbol.iterator](){yield ['name',fields.name.value];yield ['config',fields.config.value];yield ['token','test-session'];}},
 fetch:async(url,options)=>{requests.push(new URLSearchParams(options.body));return {ok,text:async()=>ok?'Profil gespeichert':'Ungültige Konfiguration'};}
});
(async()=>{
 const config='[Interface]\n# Grüße 📺\nPrivateKey = ab+/==\n';
 fields.file.files=[{name:'Proton.conf',size:Buffer.byteLength(config),text:async()=>config}];
 await fields.file.handlers.change();
 assert.equal(fields.config.value,config);assert.equal(fields.name.value,'Proton');
 await fields.form.handlers.submit({preventDefault(){}});
 assert.equal(requests[0].get('config'),config);assert.equal(fields.config.value,'');assert.equal(fields.form.hidden,true);
 fields.form.hidden=false;fields.config.value=config;fields.name.value='Eingefügt';ok=false;
 await fields.form.handlers.submit({preventDefault(){}});
 assert.equal(fields.config.value,config);assert.equal(fields.send.disabled,false);assert.equal(fields.form.hidden,false);
 ok=true;await fields.form.handlers.submit({preventDefault(){}});
 assert.equal(requests[2].get('name'),'Eingefügt');assert.equal(requests[2].get('config'),config);
 fields.file.files=[{size:65537}];await fields.file.handlers.change();assert.match(fields.result.textContent,/größer/);
 fields.config.value='ä'.repeat(32769);await fields.form.handlers.submit({preventDefault(){}});assert.equal(requests.length,3);
 console.log('PASS: file, paste, UTF-8/key characters, correction after rejection, secret clearing, file/text byte limits');
})().catch(error=>{console.error(error);process.exitCode=1;});
