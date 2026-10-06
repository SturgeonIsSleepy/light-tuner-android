const json = (body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
const packageOk = value=>typeof value==='string'&&/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value)&&value.length<=160;
const versionOk = value=>typeof value==='string'&&/^\d{1,20}$/.test(value);
const nameOk = value=>typeof value==='string'&&value.length>0&&value.length<=64&&!/[\x00-\x1f\x7f]/.test(value);
const idOk=value=>typeof value==='string'&&/^[a-f0-9-]{36}$/.test(value);
async function body(request){if(Number(request.headers.get('Content-Length'))>262144)throw Error('内容过大');const value=await request.text();if(new TextEncoder().encode(value).length>262144)throw Error('内容过大');return JSON.parse(value);}
function allowedPolicy(p){return p&&Object.keys(p).length===5&&Object.keys(p).every(k=>['packageName','versionCode','threadName','cpuMask','enabled'].includes(k))&&packageOk(p.packageName)&&versionOk(p.versionCode)&&nameOk(p.threadName)&&Number.isInteger(p.cpuMask)&&p.cpuMask>=1&&p.cpuMask<=255&&typeof p.enabled==='boolean'&&!['io.github.sturgeonissleepy.lighttuner','cc.ccwu.apptuner','com.android.systemui','me.weishu.kernelsu'].includes(p.packageName);}
export default {
  async fetch(request,env){
    const url=new URL(request.url);
    try{
      if(request.method==='GET'&&url.pathname==='/')return new Response(ADMIN,{headers:{'Content-Type':'text/html; charset=utf-8','Content-Security-Policy':"default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'",'X-Content-Type-Options':'nosniff'}});
      if(request.method==='GET'&&url.pathname==='/v1/catalog'){
        const published=await env.DB.prepare('SELECT body FROM policysets WHERE id=1').first();
        return json({schemaVersion:1,platform:'mt6991',policies:published?JSON.parse(published.body):[]});
      }
      if(request.method==='POST'&&url.pathname==='/v1/reports'){
        const limited=await env.UPLOAD_LIMIT.limit({key:'upload:'+request.headers.get('CF-Connecting-IP')});if(!limited.success)return json({error:'上传过于频繁，请稍后再试'},429);
        const report=await body(request);
        if(report.schemaVersion!==1||report.platform!=='mt6991'||!idOk(report.sessionId)||!idOk(report.clientId)||!Number.isInteger(report.durationMs)||report.durationMs<1000||report.durationMs>660000||!Array.isArray(report.threads)||report.threads.length<1||report.threads.length>1000)throw Error('录制格式无效');
        const rows=[],seen=new Set();
        for(const t of report.threads){const key=[t.packageName,t.versionCode,t.threadName].join('\t');if(!packageOk(t.packageName)||!versionOk(t.versionCode)||!nameOk(t.threadName)||!Number.isFinite(t.averageCpu)||t.averageCpu<0||t.averageCpu>800||!Number.isFinite(t.peakCpu)||t.peakCpu<0||t.peakCpu>100.1||!Number.isInteger(t.instances)||t.instances<1||t.instances>8192||seen.has(key))throw Error('线程统计无效');seen.add(key);rows.push({packageName:t.packageName,versionCode:t.versionCode,threadName:t.threadName,averageCpu:t.averageCpu,peakCpu:t.peakCpu,instances:t.instances});}
        const existing=await env.DB.prepare('SELECT client FROM reports WHERE id=?').bind(report.sessionId).first();if(existing&&existing.client!==report.clientId)return json({error:'录制编号已占用'},409);
        if(!existing){const recent=await env.DB.prepare('SELECT COUNT(*) AS count FROM reports WHERE client=? AND created>?').bind(report.clientId,Date.now()-86400000).first();if(recent.count>=20)return json({error:'今日上传次数已达上限'},429);}
        await env.DB.prepare('INSERT OR IGNORE INTO reports(id,client,platform,created,duration,payload) VALUES(?,?,?,?,?,?)').bind(report.sessionId,report.clientId,report.platform,Date.now(),report.durationMs,JSON.stringify(rows)).run();
        return json({accepted:rows.length,sessionId:report.sessionId});
      }
      if(url.pathname.startsWith('/admin/')){
        if(!env.ADMIN_TOKEN||request.headers.get('Authorization')!==`Bearer ${env.ADMIN_TOKEN}`)return json({error:'需要管理员密钥'},401);
        if(request.method==='GET'&&url.pathname==='/admin/catalog'){
          const result=await env.DB.prepare("SELECT json_extract(t.value,'$.packageName') AS packageName,json_extract(t.value,'$.versionCode') AS versionCode,json_extract(t.value,'$.threadName') AS threadName,COUNT(*) AS recordings,SUM(json_extract(t.value,'$.averageCpu')*r.duration)/SUM(r.duration) AS averageCpu,MAX(json_extract(t.value,'$.peakCpu')) AS peakCpu FROM reports r,json_each(r.payload) t GROUP BY packageName,versionCode,threadName ORDER BY recordings DESC LIMIT 1000").all();return json({threads:result.results});
        }
        if(request.method==='PUT'&&url.pathname==='/admin/policies'){
          const input=await body(request);if(!Array.isArray(input.policies)||input.policies.length>200)throw Error('最多发布 200 条策略');const seen=new Set();for(const p of input.policies){let key=[p.packageName,p.versionCode,p.threadName].join('\t');if(!allowedPolicy(p)||seen.has(key))throw Error('策略字段无效或重复');seen.add(key);}
          await env.DB.prepare('INSERT INTO policysets(id,body) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body').bind(JSON.stringify(input.policies.map(p=>({...p,updated:Date.now()})))).run();return json({published:input.policies.length});
        }
      }
      return json({error:'地址不存在'},404);
    }catch(error){return json({error:error.message||'请求失败'},400);}
  }
};
const ADMIN=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>轻调线程汇集</title><style>:root{color-scheme:light dark;font:16px system-ui}body{max-width:1000px;margin:40px auto;padding:20px}input,textarea,button{font:inherit;padding:12px;margin:8px 0;border:1px solid #888;border-radius:10px}textarea{display:block;width:96%;height:260px}table{border-collapse:collapse;width:100%}td,th{padding:10px;text-align:left;border-bottom:1px solid #888}pre{white-space:pre-wrap}.scroll{overflow:auto}</style><h1>轻调线程汇集</h1><p>录制统计只用于参考，线程名称不代表用途。核分配由管理员发布，客户端查看并确认后应用。</p><input id="token" type="password" placeholder="管理员密钥" autocomplete="off"><button id="load">读取汇集</button><button id="current">读取已发布策略</button><p id="status"></p><div class="scroll"><table><thead><tr><th>应用与版本</th><th>线程名称</th><th>录制数</th><th>平均占用</th><th>最高占用</th></tr></thead><tbody id="rows"></tbody></table></div><h2>发布线程策略</h2><p>JSON 数组，cpuMask 是 CPU0–7 的位掩码（1–255）。仅匹配指定应用版本。提交会替换当前发布集。</p><textarea id="policies" spellcheck="false">[]</textarea><button id="publish">发布客户端策略</button><script>
async function call(path,options={}){const response=await fetch(path,{...options,headers:{Authorization:'Bearer '+document.querySelector('#token').value,'Content-Type':'application/json'}});const result=await response.json();if(!response.ok)throw Error(result.error);return result}const status=document.querySelector('#status');async function action(work){try{await work()}catch(e){status.textContent=e.message}}
document.querySelector('#load').onclick=()=>action(async()=>{const data=await call('/admin/catalog');const table=document.querySelector('#rows');table.replaceChildren();for(const t of data.threads){let row=document.createElement('tr');for(const value of [t.packageName+' ('+t.versionCode+')',t.threadName,t.recordings,t.averageCpu.toFixed(2)+'%',t.peakCpu.toFixed(2)+'%']){let cell=document.createElement('td');cell.textContent=value;row.append(cell)}table.append(row)}status.textContent='已读取 '+data.threads.length+' 类线程'});
document.querySelector('#current').onclick=()=>action(async()=>{const data=await call('/v1/catalog');document.querySelector('#policies').value=JSON.stringify(data.policies.map(({updated,...p})=>p),null,2);status.textContent='已读取策略'});
document.querySelector('#publish').onclick=()=>action(async()=>{const policies=JSON.parse(document.querySelector('#policies').value);if(!confirm('发布这 '+policies.length+' 条策略并替换当前发布集？'))return;const result=await call('/admin/policies',{method:'PUT',body:JSON.stringify({policies})});status.textContent='已发布 '+result.published+' 条策略'});
</script></html>`;
