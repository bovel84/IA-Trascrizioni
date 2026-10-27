#!/usr/bin/env node
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert/strict');
const {createEnvironment}=require('./dom-stub');
const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8'),fixture=require('./fixtures/rich-results.json'),ctx=createEnvironment({html}),run=code=>vm.runInContext(code,ctx);
let checks=0;function check(label,value){assert.ok(value,label);checks++;console.log('ok  '+label);}
(async()=>{
 for(const m of html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi))run(m[1]);await run('init()');ctx.fixture=fixture;
 const rendered=run('richDocumentHtml(fixture.keypoints)');
 check('the reported pipe table becomes a real table with semantic column headings',rendered.includes('<table><thead>')&&rendered.includes('scope="col"'));
 check('mobile cells retain accessible labels for the stacked layout',rendered.includes('data-label="Tema"')&&rendered.includes('data-label="Riferimento nel documento"'));
 check('bold titles and figures render without visible Markdown punctuation',rendered.includes('<h3>PUNTI CHIAVE</h3>')&&rendered.includes('<strong>500 €</strong>')&&!rendered.includes('**'));
 check('rules, lists, and quotations are rendered as document structure',rendered.includes('<hr>')&&rendered.includes('<ul>')&&rendered.includes('<blockquote>'));
 check('all five source table rows remain present',run('richDocumentBlocks(fixture.keypoints).find(b=>b.type==="table").rows.length')===5);
 ctx.hostileText='# Titolo\n\n<script>alert(1)</script>\n\n[apri](javascript:alert(1))\n\n<img src=x onerror=alert(1)>';
 const hostile=run('richDocumentHtml(hostileText)');
 check('model HTML cannot execute scripts, images, or event handlers',!hostile.includes('<script')&&!hostile.includes('<img')&&!hostile.includes('href="javascript:')&&hostile.includes('&lt;script&gt;'));
 const safeLink=run(`richDocumentHtml('[Fonte](https://example.org/?x=1&y=2)')`);
 check('safe links escape attributes and isolate the external tab',safeLink.includes('noopener noreferrer')&&safeLink.includes('x=1&amp;y=2'));
 ctx.pipeText='| A \\| B | `x|y` |';
 check('escaped pipes and code pipes do not split table cells',run('JSON.stringify(markdownCells(pipeText))')==='["A | B","`x|y`"]');
 ctx.codeText='```text\n**literal** <tag>\n```';
 check('code blocks preserve literal markup',run('richDocumentHtml(codeText)').includes('**literal** &lt;tag&gt;'));
 const plain=run('richPlainText(fixture.keypoints)');
 check('plain-text exports remain readable without pipe separators or bold markers',plain.includes('Tema: Prezzo')&&plain.includes('Dettaglio: Street price intorno ai 500 €')&&!plain.includes('**')&&!plain.includes('|---'));
 run(`aiResults=[{action:'keypoints',title:AI_TITLES.keypoints,content:fixture.keypoints,createdAt:Date.now()}];activeAIResult='keypoints';renderAIResults();`);
 check('saved results use the same rich renderer and tool accent',ctx.__elements.get('aiResult').dataset.tool==='keypoints'&&ctx.__elements.get('aiResult').children[1].innerHTML.includes('<table>'));
 check('Markdown download retains the original reusable markup',run('analysisExportText(true)').includes('| Tema | Dettaglio |')&&!run('analysisExportText(false)').includes('|---'));
 run(`renderChart({title:'Confronto',labels:['Budget','Spese'],series:[{name:'Euro',data:[1250,800]}]});`);
 check('bar categories have distinct colors and a visible data table',run('Array.isArray(chartInstance.config.data.datasets[0].backgroundColor)')&&ctx.__elements.get('chartDataTable').innerHTML.includes('1250'));
 run("changeChartType('pie');");check('chart type can change locally without a model call',run('chartData.type')==='pie'&&run('chartInstance.config.type')==='pie');
 run(`renderChart({labels:['Budget'],series:[{name:'A',data:[2]},{name:'B',data:[3]}]});changeChartType('pie');`);
 check('incompatible pie charts preserve the valid previous diagram',run('chartData.type')==='bar'&&ctx.__elements.get('chartTypeSelect').value==='bar');
 const pdfText=[];const doc={setFont(){},setFontSize(){},setTextColor(){},setFillColor(){},rect(){},addPage(){},splitTextToSize:s=>[s],text:s=>pdfText.push(s)};ctx.pdf=doc;
 run('writeRichPdf(pdf,fixture.keypoints,20)');
 check('PDF rendering uses field names and values rather than raw Markdown tables',pdfText.some(x=>x==='Tema: Prezzo')&&pdfText.some(x=>x.includes('500 €'))&&!pdfText.some(x=>x.includes('|---')||x.includes('**')));
 console.log(`${checks} rich-result checks passed.`);
})().then(()=>process.exit(0)).catch(e=>{console.error(e);process.exit(1)});
