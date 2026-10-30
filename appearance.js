/* Apply the saved appearance before paint. No network or external dependency. */
(function () {
    const root=document.documentElement,system=window.matchMedia?.('(prefers-color-scheme: light)');
    let preference='dark';try{preference=localStorage.getItem('voicescribe_theme')||'dark';}catch(e){}
    const valid=value=>['light','dark','system'].includes(value)?value:'dark';
    function apply(value,save=false){
        preference=valid(value);const theme=preference==='system'?(system?.matches?'light':'dark'):preference;
        root.dataset.theme=theme;root.style.colorScheme=theme;
        document.querySelector('meta[name="theme-color"]')?.setAttribute('content',theme==='light'?'#f4f7f6':'#101519');
        const button=document.getElementById('themeToggle'),select=document.getElementById('themeSelect');
        if(button){button.textContent=theme==='light'?'☾':'☀';button.setAttribute('aria-label',theme==='light'?'Attiva tema scuro':'Attiva tema chiaro');button.title=button.getAttribute('aria-label');}
        if(select)select.value=preference;
        if(save)try{localStorage.setItem('voicescribe_theme',preference);}catch(e){}
        if(typeof window.AndroidRecorder?.setTheme==='function')window.AndroidRecorder.setTheme(theme);
        window.dispatchEvent(new CustomEvent('voicescribe:themechange',{detail:{theme}}));
    }
    window.initAppearance=()=>{apply(preference);document.getElementById('themeToggle').onclick=()=>apply(root.dataset.theme==='dark'?'light':'dark',true);document.getElementById('themeSelect').onchange=event=>apply(event.target.value,true);};
    system?.addEventListener?.('change',()=>{if(preference==='system')apply(preference);});apply(preference);
})();
