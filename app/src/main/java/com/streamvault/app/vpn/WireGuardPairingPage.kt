package com.streamvault.app.vpn

internal object WireGuardPairingPage {
    // Token is generated internally from hex; no imported text is interpolated into HTML.
    fun html(token: String) = """
        <!doctype html>
        <html lang="de"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>StreamVault · WireGuard</title>
        <style>
        *{box-sizing:border-box}body{font-family:system-ui,sans-serif;background:#101820;color:#f8fafc;margin:0;padding:20px}
        main{max-width:560px;margin:auto;background:#172635;border:1px solid #2b4258;border-radius:22px;padding:22px}
        h1{font-size:25px;margin-top:0}p{line-height:1.5;color:#b9c6d3}label{display:block;margin-top:18px;font-weight:700}
        input,textarea,button{width:100%;font:inherit;margin-top:8px;padding:13px;border-radius:12px;border:1px solid #39546d;background:#0c1620;color:white}
        textarea{min-height:220px;resize:vertical;font-family:monospace;font-size:14px}button{background:#32d6a0;color:#06110d;font-weight:800;cursor:pointer}
        button:disabled{opacity:.5}#result{white-space:pre-wrap}input[type=file]{padding:10px}
        </style></head><body><main>
        <h1>WireGuard hinzufügen</h1>
        <p>Wie bei Xtream: auf dem Handy ausfüllen und direkt an den TV senden.
        Beide Geräte müssen im selben WLAN sein. Die lokale Übertragung verwendet HTTP; nur in einem vertrauenswürdigen Netz nutzen.</p>
        <form id="form" method="post" action="/submit" autocomplete="off">
        <input type="hidden" name="token" value="$token">
        <label for="name">Profilname</label><input id="name" name="name" maxlength="64" placeholder="z. B. Proton VPN">
        <label for="file">.conf-Datei auswählen</label><input id="file" type="file" accept=".conf,.txt,text/plain,application/octet-stream">
        <p>Oder den vollständigen Konfigurationstext unten einfügen. Eine ausgewählte Datei wird in dieses Feld übernommen.</p>
        <label for="config">WireGuard-Konfiguration</label><textarea id="config" name="config" required spellcheck="false" autocapitalize="off" autocomplete="off" placeholder="[Interface]&#10;PrivateKey = …&#10;…"></textarea>
        <button id="send" type="submit">An TV senden</button>
        </form><p id="result" role="status" aria-live="polite"></p></main>
        <script>
        const form=document.getElementById('form'), file=document.getElementById('file'), config=document.getElementById('config');
        const nameField=document.getElementById('name'), send=document.getElementById('send'), result=document.getElementById('result');
        file.addEventListener('change',async()=>{
          const selected=file.files[0]; if(!selected)return;
          if(selected.size>65536){result.textContent='Datei ist größer als 64 KiB.';file.value='';return;}
          send.disabled=true;
          try{config.value=await selected.text();if(!nameField.value)nameField.value=selected.name.replace(/\.conf$/i,'').slice(0,64);result.textContent='Datei geladen. Jetzt an TV senden.';}
          catch(e){result.textContent='Datei konnte nicht gelesen werden. Bitte den Text einfügen.';}
          finally{send.disabled=false;}
        });
        form.addEventListener('submit',async(event)=>{
          event.preventDefault();if(new Blob([config.value]).size>65536){result.textContent='Konfiguration ist größer als 64 KiB.';return;}
          send.disabled=true;result.textContent='Wird an TV gesendet …';
          try{
            const response=await fetch('/submit',{method:'POST',body:new URLSearchParams(new FormData(form)),cache:'no-store'});
            result.textContent=await response.text();
            if(response.ok){config.value='';file.value='';form.hidden=true;}else{send.disabled=false;}
          }catch(e){result.textContent='TV nicht erreichbar oder QR-Sitzung beendet. Am TV prüfen, ob das Profil gespeichert wurde; gegebenenfalls neuen QR-Code öffnen.';send.disabled=false;}
        });
        </script></body></html>
    """.trimIndent()
}
