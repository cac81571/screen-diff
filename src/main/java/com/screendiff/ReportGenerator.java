package com.screendiff;

import com.opencsv.CSVWriter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public class ReportGenerator {

    public enum ImageFormat {
        ORIGINAL,
        JPEG
    }

    public static void writeCsv(List<ImageComparator.Result> results, File output) throws IOException {
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(new FileOutputStream(output), "UTF-8"))) {
            writer.writeNext(new String[]{
                    "ファイル名", "ピクセル差異(%)", "画像サイズ差異(%)", "テキスト差異(%)",
                    "旧画像幅", "新画像幅", "旧画像高さ", "新画像高さ"
            });
            for (var r : results) {
                writer.writeNext(new String[]{
                        r.fileName(),
                        String.format("%.2f", r.diffPercent()),
                        String.format("%.1f", r.sizeDiffPercent()),
                        formatTextDiffCsv(r.textDiffPercent()),
                        String.valueOf(r.oldWidth()),
                        String.valueOf(r.newWidth()),
                        String.valueOf(r.oldHeight()),
                        String.valueOf(r.newHeight())
                });
            }
        }
    }

    public static void writeHtml(
            List<ImageComparator.Result> results,
            File oldDir,
            File newDir,
            File output,
            ImageFormat imageFormat,
            float jpegQuality,
            int splitHeadHeight,
            int splitTailHeight) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html><html><head><meta charset='UTF-8'><title>Screen Diff Report</title>
                <style>
                *{box-sizing:border-box}
                body{font-family:sans-serif;margin:0;padding-top:var(--header-offset,95px);background:#fff}
                .header{position:fixed;top:0;left:0;right:0;background:#e8eef5;border-bottom:1px solid #b8c4d4;box-shadow:0 1px 3px rgba(0,0,0,.08);padding:10px 20px;z-index:1000;display:flex;align-items:center;gap:15px;flex-wrap:wrap}
                .header h1{margin:0;font-size:16px;white-space:nowrap}
                .view-mode{display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap}
                .view-mode label{margin:0;white-space:nowrap;font-weight:normal;font-size:14px}
                .page-filter{display:inline-flex;align-items:center;gap:6px;margin:0;white-space:nowrap;font-weight:normal;font-size:14px}
                .page-filter input[type=text]{width:220px;max-width:40vw;font-size:13px;padding:4px 6px}
                .page-filter .page-filter-count{font-size:12px;color:#555;margin-left:4px}
                .content{padding:20px}
                .diff-section{margin-bottom:16px;scroll-margin-top:var(--header-offset,95px)}
                .diff-section h2{margin:0 0 4px;font-size:16px;font-weight:bold;line-height:1.3}
                .pair{display:flex;gap:10px;width:100%;margin:0}
                .pair>div{flex:1;min-width:0}
                .pair p{margin:0 0 2px;font-size:13px}
                .pair.single-new>div.old-panel{display:none}
                .pair.single-new>div.new-panel{flex:1}
                .pair.single-old>div.new-panel{display:none}
                .pair.single-old>div.old-panel{flex:1}
                .img-wrap{position:relative;display:inline-block;width:100%;overflow:hidden;cursor:zoom-in;border:1px solid #888;background:#fafafa}
                .img-wrap.zoomed{cursor:grab}
                .img-wrap.zoomed.dragging{cursor:grabbing;user-select:none}
                .img-wrap.zoomed img{image-rendering:pixelated;image-rendering:crisp-edges}
                .img-zoom-inner{position:relative;display:block;width:fit-content;max-width:100%;margin:0 auto;transform-origin:0 0}
                .img-wrap.zoomed .img-zoom-inner{margin:0}
                .img-wrap img{width:auto;height:auto;max-width:100%;display:block;border:0}
                .img-wrap .overlay{position:absolute;top:0;left:0;width:100%;height:100%;display:none;pointer-events:none;opacity:0.5}
                .img-wrap .blink-img{position:absolute;top:0;left:0;width:100%;height:100%;display:none;pointer-events:none}
                .text-diff{margin-top:8px;border:1px solid #ccc;border-radius:4px;background:#fafafa}
                .text-diff summary{padding:8px 12px;cursor:pointer;font-size:13px;font-weight:bold;user-select:none;list-style:disclosure-closed inside}
                .text-diff[open] summary{border-bottom:1px solid #ddd;list-style:disclosure-open inside}
                .text-diff-body{padding:8px 12px 12px}
                .text-diff-empty{margin:0;font-size:13px;color:#555}
                .text-diff-stats{margin:0 0 8px;font-size:12px;color:#555}
                .text-diff-table{width:100%;border-collapse:collapse;font-size:12px;font-family:Consolas,monospace}
                .text-diff-table th,.text-diff-table td{border:1px solid #ddd;padding:4px 6px;vertical-align:top;white-space:pre-wrap;word-break:break-all;width:50%}
                .text-diff-table th{background:#e8eef5;text-align:left;font-family:sans-serif}
                .text-diff-table tr.omitted td{background:#f0f0f0;color:#666;text-align:center;font-family:sans-serif;font-style:italic}
                .text-diff-table tr.changed td.old{background:#fee}
                .text-diff-table tr.changed td.new{background:#efe}
                .text-diff-table tr.removed td.old{background:#fdd}
                .text-diff-table tr.removed td.new{background:#fafafa;color:#999}
                .text-diff-table tr.added td.old{background:#fafafa;color:#999}
                .text-diff-table tr.added td.new{background:#dfd}
                .report-summary{margin-bottom:16px;border:1px solid #b8c4d4;border-radius:4px;background:#fafafa;scroll-margin-top:var(--header-offset,95px)}
                .report-summary>summary{padding:10px 14px;cursor:pointer;font-weight:bold;font-size:15px;user-select:none;list-style:disclosure-closed inside}
                .report-summary[open]>summary{border-bottom:1px solid #ddd;list-style:disclosure-open inside}
                .summary-panel{padding:12px 14px 14px}
                .summary-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:0 0 8px;font-size:14px}
                .summary-filters{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:0 0 12px;font-size:14px}
                .summary-hint{margin:0 0 10px;font-size:12px;color:#444}
                .summary-table{width:100%;border-collapse:collapse;font-size:13px}
                .summary-table th,.summary-table td{border:1px solid #ccc;padding:6px 8px;text-align:left}
                .summary-table th{background:#e8eef5;cursor:pointer;user-select:none;white-space:nowrap}
                .summary-table th:hover{background:#d8e4f0}
                .summary-table td.num{text-align:right;font-variant-numeric:tabular-nums}
                .summary-table tr:hover td{background:#f5f8fc}
                .summary-table a{color:#06c;text-decoration:none}
                .summary-table a:hover{text-decoration:underline}
                </style>
                <script>
                var totalImages = 0;
                function syncHeaderOffset(){
                  var header=document.getElementById('report_header');
                  if(!header){return;}
                  var h=header.offsetHeight;
                  document.documentElement.style.setProperty('--header-offset',h+'px');
                }
                function initHeaderOffset(){
                  syncHeaderOffset();
                  var header=document.getElementById('report_header');
                  if(!header){return;}
                  if(typeof ResizeObserver!=='undefined'){
                    new ResizeObserver(syncHeaderOffset).observe(header);
                  }
                  window.addEventListener('resize',syncHeaderOffset);
                }
                function toggleDiffOverlayAll(checked){
                  for(var i=0;i<totalImages;i++){
                    document.getElementById('diff_old_'+i).style.display=checked?'block':'none';
                    document.getElementById('diff_new_'+i).style.display=checked?'block':'none';
                  }
                }
                function toggleImgOverlayAll(checked){
                  for(var i=0;i<totalImages;i++){
                    document.getElementById('img_old_on_new_'+i).style.display=checked?'block':'none';
                    document.getElementById('img_new_on_old_'+i).style.display=checked?'block':'none';
                  }
                }
                function setOpacityAll(){
                  var val=document.getElementById('opacity_all').value;
                  document.getElementById('opacity_val_all').textContent=val+'%';
                  var overlays=document.querySelectorAll('.overlay');
                  overlays.forEach(function(el){el.style.opacity=val/100;});
                }
                var blinkTimers={};
                function toggleBlinkAll(checked){
                  for(var i=0;i<totalImages;i++) toggleBlink(i,checked);
                }
                function toggleBlink(idx,checked){
                  var oldOnNew=document.getElementById('blink_old_on_new_'+idx);
                  var newOnOld=document.getElementById('blink_new_on_old_'+idx);
                  if(checked){
                    oldOnNew.style.display='none';
                    newOnOld.style.display='block';
                    var showOld=false;
                    blinkTimers[idx]=setInterval(function(){
                      showOld=!showOld;
                      oldOnNew.style.display=showOld?'block':'none';
                      newOnOld.style.display=showOld?'none':'block';
                    },parseInt(document.getElementById('blink_speed_all').value));
                  }else{
                    clearInterval(blinkTimers[idx]);
                    oldOnNew.style.display='none';
                    newOnOld.style.display='none';
                  }
                }
                function setBlinkSpeedAll(){
                  var val=document.getElementById('blink_speed_all').value;
                  document.getElementById('blink_speed_val_all').textContent=val+'ms';
                  var cb=document.getElementById('blink_cb_all');
                  if(cb.checked){toggleBlinkAll(false);toggleBlinkAll(true);}
                }
                function setViewMode(mode){
                  var pairs=document.querySelectorAll('.pair');
                  pairs.forEach(function(p){
                    p.classList.remove('single-new','single-old');
                    if(mode==='single-new') p.classList.add('single-new');
                    else if(mode==='single-old') p.classList.add('single-old');
                  });
                  resetAllInlineZoom();
                }
                function normalizePageSearchQuery(q){
                  return (q||'').trim().toLowerCase();
                }
                function fuzzyMatchFileName(fileName,query){
                  if(!query){return true;}
                  var name=(fileName||'').toLowerCase();
                  if(name.indexOf(query)>=0){return true;}
                  var qi=0;
                  for(var ni=0;ni<name.length&&qi<query.length;ni++){
                    if(name.charAt(ni)===query.charAt(qi)){qi++;}
                  }
                  return qi===query.length;
                }
                function onPageFilterInput(){
                  applyPageFilter(false);
                }
                function updatePageFilterValue(fileName){
                  var pf=document.getElementById('page_filter');
                  if(pf){pf.value=fileName==null?'':String(fileName);}
                }
                function setPageFilter(fileName){
                  updatePageFilterValue(fileName);
                  applyPageFilter(false);
                }
                window.setPageFilter=setPageFilter;
                function revealDiffIndex(index){
                  var target=String(index);
                  document.querySelectorAll('.diff-section').forEach(function(s){
                    s.style.display=s.dataset.index===target?'':'none';
                  });
                  var pf=document.getElementById('page_filter');
                  var countEl=document.getElementById('page_filter_count');
                  if(countEl&&pf){
                    var q=normalizePageSearchQuery(pf.value);
                    countEl.textContent=q?(' 1件'):'';
                  }
                }
                function goToDiff(index,fileName){
                  var el=document.getElementById('diff-'+index);
                  var name=(fileName!=null&&String(fileName)!=='')?String(fileName):(el&&el.dataset.file?el.dataset.file:'');
                  updatePageFilterValue(name);
                  resetAllInlineZoom();
                  revealDiffIndex(index);
                  el=document.getElementById('diff-'+index);
                  if(el){el.scrollIntoView({behavior:'smooth',block:'start'});}
                }
                function showDiff(index){
                  var row=document.querySelector('#summary_body tr[data-index="'+index+'"]');
                  goToDiff(index,row?row.dataset.file:'');
                }
                function scrollToDiffFromHash(){
                  var hash=location.hash;
                  if(!hash||hash.indexOf('#diff-')!==0){return;}
                  var el=document.querySelector(hash);
                  if(!el||el.dataset.index==null){return;}
                  goToDiff(parseInt(el.dataset.index,10),el.dataset.file||'');
                }
                function summaryTextSortKey(row){
                  var t=parseFloat(row.dataset.text);
                  return t<0?-1:t;
                }
                function compareSummaryRows(a,b,mode){
                  switch(mode){
                    case 'name-asc':
                      return (a.dataset.file||'').localeCompare(b.dataset.file||'','ja');
                    case 'pixel-desc':
                      return parseFloat(b.dataset.pixel)-parseFloat(a.dataset.pixel);
                    case 'size-desc':
                      return parseFloat(b.dataset.size)-parseFloat(a.dataset.size);
                    case 'text-desc':
                      return summaryTextSortKey(b)-summaryTextSortKey(a);
                    default:
                      return parseInt(a.dataset.index,10)-parseInt(b.dataset.index,10);
                  }
                }
                function summaryRowMatchesFilter(row,pixelVal,sizeVal,textVal){
                  var pixel=parseFloat(row.dataset.pixel);
                  var size=parseFloat(row.dataset.size);
                  var text=parseFloat(row.dataset.text);
                  return pixel>=pixelVal&&size>=sizeVal&&(text<0||text>=textVal);
                }
                function filterSummaryByMetric(inputId,valId){
                  var val=document.getElementById(inputId).value;
                  document.getElementById(valId).textContent=val+'%';
                  applySummaryFilters();
                }
                function applySummaryFilters(){
                  var pixelVal=parseFloat(document.getElementById('filter_pixel').value);
                  var sizeVal=parseFloat(document.getElementById('filter_size').value);
                  var textVal=parseFloat(document.getElementById('filter_text').value);
                  var rows=document.querySelectorAll('#summary_body tr');
                  var shown=0;
                  rows.forEach(function(r){
                    var show=summaryRowMatchesFilter(r,pixelVal,sizeVal,textVal);
                    r.style.display=show?'':'none';
                    if(show){shown++;}
                  });
                  document.getElementById('summary_count').textContent='表示: '+shown+' / 全 '+rows.length;
                }
                function applySummarySort(){
                  var mode=document.getElementById('sort_order').value;
                  var tbody=document.getElementById('summary_body');
                  var rows=Array.from(tbody.querySelectorAll('tr'));
                  rows.sort(function(a,b){return compareSummaryRows(a,b,mode);});
                  rows.forEach(function(r){tbody.appendChild(r);});
                  applySummaryFilters();
                }
                function isSummaryFilterSlider(el){
                  return el.id==='filter_pixel'||el.id==='filter_size'||el.id==='filter_text';
                }
                function applyPageFilter(scrollToFirst){
                  var query=normalizePageSearchQuery(document.getElementById('page_filter').value);
                  var sections=document.querySelectorAll('.diff-section');
                  var shown=0;
                  var firstMatch=null;
                  sections.forEach(function(s){
                    var show=fuzzyMatchFileName(s.dataset.file,query);
                    s.style.display=show?'':'none';
                    if(show){
                      shown++;
                      if(!firstMatch){firstMatch=s;}
                    }
                  });
                  var countEl=document.getElementById('page_filter_count');
                  if(countEl){countEl.textContent=query?(' '+shown+'件'):'';}
                  if(scrollToFirst&&firstMatch){
                    firstMatch.scrollIntoView({behavior:'smooth',block:'start'});
                  }
                }
                function adjustSliderByKeyboard(el,direction,stepOverride){
                  var min=parseFloat(el.min);
                  var max=parseFloat(el.max);
                  var step=stepOverride!=null?stepOverride:(max-min)*0.05;
                  var val=parseFloat(el.value)+direction*step;
                  val=Math.max(min,Math.min(max,val));
                  var stepAttr=parseFloat(el.getAttribute('step'));
                  if(!isNaN(stepAttr)&&stepAttr<1){val=Math.round(val*10)/10;}
                  else{val=Math.round(val);}
                  el.value=val;
                  el.dispatchEvent(new Event('input',{bubbles:true}));
                }
                document.addEventListener('keydown',function(e){
                  if(e.key!=='ArrowLeft'&&e.key!=='ArrowRight'){return;}
                  var el=document.activeElement;
                  if(!el||el.tagName!=='INPUT'||el.type!=='range'){return;}
                  if(e.altKey||e.metaKey){return;}
                  if(isSummaryFilterSlider(el)){
                    e.preventDefault();
                    var step=e.ctrlKey?5:1;
                    adjustSliderByKeyboard(el,e.key==='ArrowRight'?1:-1,step);
                    return;
                  }
                  if(!e.ctrlKey){return;}
                  e.preventDefault();
                  adjustSliderByKeyboard(el,e.key==='ArrowRight'?1:-1,null);
                });
                function isDualView(){
                  var checked=document.querySelector('input[name="view_mode"]:checked');
                  return checked&&checked.value==='dual';
                }
                function getSyncedWraps(wrap){
                  if(!isDualView()){return[wrap];}
                  var pair=wrap.closest('.pair');
                  if(!pair){return[wrap];}
                  return Array.from(pair.querySelectorAll('.img-wrap'));
                }
                function getActualSizeScale(wrap){
                  var base=wrap.querySelector('.img-zoom-inner img');
                  if(!base||!base.naturalWidth||!base.naturalHeight){return 1;}
                  var displayW=base.offsetWidth;
                  if(displayW<=0){return 1;}
                  var scale=base.naturalWidth/displayW;
                  return scale<=1?1:scale;
                }
                function updateWrapTransform(wrap){
                  var inner=wrap.querySelector('.img-zoom-inner');
                  if(!inner||!wrap.classList.contains('zoomed')){return;}
                  var viewW=wrap.clientWidth;
                  var viewH=wrap.clientHeight;
                  var contentW=inner.offsetWidth;
                  var contentH=inner.offsetHeight;
                  if(viewW<=0||viewH<=0||contentW<=0||contentH<=0){return;}
                  var ratioX=parseFloat(wrap.dataset.ratioX||'0.5');
                  var ratioY=parseFloat(wrap.dataset.ratioY||'0.5');
                  var panX=parseFloat(wrap.dataset.panX||'0');
                  var panY=parseFloat(wrap.dataset.panY||'0');
                  var s=parseFloat(wrap.dataset.scale||'1');
                  var px=ratioX*contentW;
                  var py=ratioY*contentH;
                  var tx=viewW/2-px*s+panX;
                  var ty=viewH/2-py*s+panY;
                  var scaledW=contentW*s;
                  var scaledH=contentH*s;
                  if(scaledW>viewW){
                    var minTx=viewW-scaledW;
                    tx=Math.min(0,Math.max(minTx,tx));
                    wrap.dataset.panX=String(tx-(viewW/2-px*s));
                  }else{
                    tx=(viewW-scaledW)/2;
                    wrap.dataset.panX='0';
                  }
                  if(scaledH>viewH){
                    var minTy=viewH-scaledH;
                    ty=Math.min(0,Math.max(minTy,ty));
                    wrap.dataset.panY=String(ty-(viewH/2-py*s));
                  }else{
                    ty=(viewH-scaledH)/2;
                    wrap.dataset.panY='0';
                  }
                  inner.style.transformOrigin='0 0';
                  inner.style.transform='translate('+tx+'px,'+ty+'px) scale('+s+')';
                }
                function applyInlineZoomToWrap(wrap,ratioX,ratioY,resetPan){
                  var inner=wrap.querySelector('.img-zoom-inner');
                  if(!inner){return;}
                  wrap.dataset.ratioX=String(ratioX);
                  wrap.dataset.ratioY=String(ratioY);
                  if(resetPan!==false){
                    wrap.dataset.panX='0';
                    wrap.dataset.panY='0';
                  }
                  wrap.dataset.scale=String(getActualSizeScale(wrap));
                  wrap.classList.add('zoomed');
                  updateWrapTransform(wrap);
                }
                function clearWrapZoomState(wrap){
                  wrap.classList.remove('zoomed','dragging');
                  var inner=wrap.querySelector('.img-zoom-inner');
                  if(!inner){return;}
                  inner.style.transform='';
                  inner.style.transformOrigin='';
                  wrap.dataset.scale='1';
                  delete wrap.dataset.ratioX;
                  delete wrap.dataset.ratioY;
                  delete wrap.dataset.panX;
                  delete wrap.dataset.panY;
                }
                var zoomDragState=null;
                var suppressZoomClick=false;
                function initImageZoom(){
                  document.querySelectorAll('.img-wrap').forEach(function(wrap){
                    wrap.addEventListener('click',function(e){
                      if(suppressZoomClick){suppressZoomClick=false;return;}
                      if(e.target.closest('a,button,input,label')){return;}
                      zoomInlineAtClick(wrap,e);
                    });
                    wrap.addEventListener('mousedown',function(e){
                      if(!wrap.classList.contains('zoomed')||e.button!==0){return;}
                      if(e.target.closest('a,button,input,label')){return;}
                      e.preventDefault();
                      var wraps=getSyncedWraps(wrap);
                      zoomDragState={
                        wraps:wraps,
                        startX:e.clientX,
                        startY:e.clientY,
                        startPanX:wraps.map(function(w){return parseFloat(w.dataset.panX||'0');}),
                        startPanY:wraps.map(function(w){return parseFloat(w.dataset.panY||'0');}),
                        moved:false
                      };
                      wraps.forEach(function(w){w.classList.add('dragging');});
                    });
                  });
                  document.addEventListener('mousemove',function(e){
                    if(!zoomDragState){return;}
                    var dx=e.clientX-zoomDragState.startX;
                    var dy=e.clientY-zoomDragState.startY;
                    if(Math.abs(dx)>3||Math.abs(dy)>3){zoomDragState.moved=true;}
                    zoomDragState.wraps.forEach(function(w,i){
                      w.dataset.panX=String(zoomDragState.startPanX[i]+dx);
                      w.dataset.panY=String(zoomDragState.startPanY[i]+dy);
                      updateWrapTransform(w);
                    });
                  });
                  document.addEventListener('mouseup',function(){
                    if(!zoomDragState){return;}
                    if(zoomDragState.moved){suppressZoomClick=true;}
                    zoomDragState.wraps.forEach(function(w){w.classList.remove('dragging');});
                    zoomDragState=null;
                  });
                }
                function resetInlineZoom(wrap){
                  getSyncedWraps(wrap).forEach(clearWrapZoomState);
                }
                function resetAllInlineZoom(){
                  document.querySelectorAll('.img-wrap').forEach(clearWrapZoomState);
                }
                function zoomInlineAtClick(wrap,e){
                  if(wrap.classList.contains('zoomed')){
                    resetInlineZoom(wrap);
                    return;
                  }
                  var inner=wrap.querySelector('.img-zoom-inner');
                  var base=inner?inner.querySelector('img'):null;
                  if(!inner||!base||!base.src||!base.naturalWidth){return;}
                  var innerRect=inner.getBoundingClientRect();
                  var clickX=e.clientX-innerRect.left;
                  var clickY=e.clientY-innerRect.top;
                  var contentW=inner.offsetWidth;
                  var contentH=inner.offsetHeight;
                  if(contentW<=0||contentH<=0){return;}
                  var ratioX=Math.max(0,Math.min(1,clickX/contentW));
                  var ratioY=Math.max(0,Math.min(1,clickY/contentH));
                  getSyncedWraps(wrap).forEach(function(w){
                    applyInlineZoomToWrap(w,ratioX,ratioY,true);
                  });
                }
                document.addEventListener('keydown',function(e){
                  if(e.key==='Escape'){resetAllInlineZoom();}
                });
                </script>
                </head><body>
                <div class='header' id='report_header'>
                <h1>画面比較レポート</h1>
                <div class='view-mode'>
                <span>表示方法</span>
                <label><input type='radio' name='view_mode' value='dual' checked onchange="setViewMode(this.value)"> 両方</label>
                <label><input type='radio' name='view_mode' value='single-old' onchange="setViewMode(this.value)"> 旧のみ</label>
                <label><input type='radio' name='view_mode' value='single-new' onchange="setViewMode(this.value)"> 新のみ</label>
                </div>
                <label class='page-filter'>表示ページ:<input type='text' id='page_filter' placeholder='ファイル名で検索（空で全件）' autocomplete='off' oninput='onPageFilterInput()'><span id='page_filter_count' class='page-filter-count'></span></label>
                <label><input type='checkbox' onchange="toggleDiffOverlayAll(this.checked)"> 差分を重ねる</label>
                <label><input type='checkbox' onchange="toggleImgOverlayAll(this.checked)"> 新旧を重ねる</label>
                <label>透明度:<input id='opacity_all' type='range' min='0' max='100' value='50' oninput="setOpacityAll()"><span id='opacity_val_all'>50%</span></label>
                <label><input id='blink_cb_all' type='checkbox' onchange="toggleBlinkAll(this.checked)"> 交互表示</label>
                <label>速度:<input id='blink_speed_all' type='range' min='10' max='500' value='100' oninput="setBlinkSpeedAll()"><span id='blink_speed_val_all'>100ms</span></label>
                <span style='font-size:12px;color:#444'>クリックで最大100%表示（実寸まで・それ以上拡大しない）／拡大中クリックで戻す・ドラッグで移動</span>
                </div>
                <div class='content'>
                """);
        appendHtmlSummaryAccordion(sb, results);
        sb.append("<div id='report_sections'>");

        int idx = 0;
        StringBuilder imageDataJson = new StringBuilder();
        for (var r : results) {
            String diffOldId = "diff_old_" + idx;
            String diffNewId = "diff_new_" + idx;
            String imgOldOnNewId = "img_old_on_new_" + idx;
            String imgNewOnOldId = "img_new_on_old_" + idx;
            String diffSrc = toDataUriPng(ImageDisplaySplitter.apply(r.diffOverlayImage(), splitHeadHeight, splitTailHeight));
            String oldSrc = encodeReportImage(r.reportOldImage(), new File(oldDir, r.fileName()), imageFormat, jpegQuality, splitHeadHeight, splitTailHeight);
            String newSrc = encodeReportImage(r.reportNewImage(), new File(newDir, r.fileName()), imageFormat, jpegQuality, splitHeadHeight, splitTailHeight);

            if (idx > 0) {
                imageDataJson.append(',');
            }
            imageDataJson.append("{\"o\":").append(toJsString(oldSrc))
                    .append(",\"n\":").append(toJsString(newSrc))
                    .append(",\"d\":").append(toJsString(diffSrc))
                    .append('}');

            sb.append("<div class='diff-section' id='diff-").append(idx)
              .append("' data-index='").append(idx)
              .append("' data-file='").append(escapeHtmlAttr(r.fileName()))
              .append("' data-pixel='").append(String.format("%.2f", r.diffPercent()))
              .append("' data-size='").append(String.format("%.1f", r.sizeDiffPercent()))
              .append("' data-text='").append(formatTextDiffData(r)).append("'>")
              .append("<h2>").append(formatSectionTitle(r)).append("</h2>")
              .append("<div class='pair'>")
              .append("<div class='old-panel'><p>旧</p><div class='img-wrap'><div class='img-zoom-inner'><img id='base_old_").append(idx).append("'>")
              .append("<img id='").append(diffOldId).append("' class='overlay'>")
              .append("<img id='").append(imgNewOnOldId).append("' class='overlay'>")
              .append("<img id='blink_new_on_old_").append(idx).append("' class='blink-img'>")
              .append("</div></div></div>")
              .append("<div class='new-panel'><p>新</p><div class='img-wrap'><div class='img-zoom-inner'><img id='base_new_").append(idx).append("'>")
              .append("<img id='").append(diffNewId).append("' class='overlay'>")
              .append("<img id='").append(imgOldOnNewId).append("' class='overlay'>")
              .append("<img id='blink_old_on_new_").append(idx).append("' class='blink-img'>")
              .append("</div></div></div>")
              .append("</div>");
            appendTextDiffAccordion(sb, oldDir, newDir, r);
            sb.append("</div>");
            idx++;
        }
        sb.append("""
                </div>
                <script>
                totalImages=%d;
                var reportImages=[%s];
                function applyReportImages(){
                  for(var i=0;i<reportImages.length;i++){
                    var d=reportImages[i];
                    document.getElementById('base_old_'+i).src=d.o;
                    document.getElementById('diff_old_'+i).src=d.d;
                    document.getElementById('img_new_on_old_'+i).src=d.n;
                    document.getElementById('blink_new_on_old_'+i).src=d.n;
                    document.getElementById('base_new_'+i).src=d.n;
                    document.getElementById('diff_new_'+i).src=d.d;
                    document.getElementById('img_old_on_new_'+i).src=d.o;
                    document.getElementById('blink_old_on_new_'+i).src=d.o;
                  }
                }
                applyReportImages();
                initImageZoom();
                initHeaderOffset();
                applySummaryFilters();
                scrollToDiffFromHash();
                window.addEventListener('hashchange',scrollToDiffFromHash);
                </script>
                """.formatted(idx, imageDataJson));
        sb.append("</div></body></html>");
        Files.writeString(output.toPath(), sb.toString());
    }

    private static void appendHtmlSummaryAccordion(StringBuilder sb, List<ImageComparator.Result> results) {
        sb.append("<details class='report-summary' id='report_summary' open>")
          .append("<summary>差分一覧サマリ（").append(results.size()).append("件）</summary>")
          .append("<div class='summary-panel'>")
          .append("<p class='summary-hint'>ファイル名をクリックすると「表示ページ」に設定し、該当の画像比較へ移動します。</p>")
          .append("<div class='summary-toolbar'>")
          .append("<label>並び替え:<select id='sort_order' onchange='applySummarySort()'>")
          .append("<option value='original' selected>比較順（既定）</option>")
          .append("<option value='name-asc'>ファイル名（昇順）</option>")
          .append("<option value='pixel-desc'>ピクセル差分（降順）</option>")
          .append("<option value='size-desc'>画像サイズ差分（降順）</option>")
          .append("<option value='text-desc'>テキスト差分（降順）</option>")
          .append("</select></label>")
          .append("<span id='summary_count'>表示: ").append(results.size())
          .append(" / 全 ").append(results.size()).append("</span>")
          .append("</div>")
          .append("<div class='summary-filters'>")
          .append("<span>差分フィルタ</span>")
          .append("<label>ピクセル:<input id='filter_pixel' type='range' min='0' max='100' value='0' step='0.1' ")
          .append("oninput=\"filterSummaryByMetric('filter_pixel','filter_pixel_val')\"><span id='filter_pixel_val'>0%</span>以上</label>")
          .append("<label>画像サイズ:<input id='filter_size' type='range' min='0' max='100' value='0' step='0.1' ")
          .append("oninput=\"filterSummaryByMetric('filter_size','filter_size_val')\"><span id='filter_size_val'>0%</span>以上</label>")
          .append("<label>テキスト:<input id='filter_text' type='range' min='0' max='100' value='0' step='0.1' ")
          .append("oninput=\"filterSummaryByMetric('filter_text','filter_text_val')\"><span id='filter_text_val'>0%</span>以上</label>")
          .append("<span style='font-size:12px;color:#444'>フィルタスライダは ← / → で 1% 刻み、Ctrl+← / Ctrl+→ で 5% 刻み</span>")
          .append("</div>")
          .append("<table class='summary-table'><thead><tr>")
          .append("<th onclick=\"document.getElementById('sort_order').value='name-asc';applySummarySort()\">ファイル名</th>")
          .append("<th onclick=\"document.getElementById('sort_order').value='pixel-desc';applySummarySort()\">ピクセル差異(%)</th>")
          .append("<th onclick=\"document.getElementById('sort_order').value='size-desc';applySummarySort()\">画像サイズ差異(%)</th>")
          .append("<th onclick=\"document.getElementById('sort_order').value='text-desc';applySummarySort()\">テキスト差異(%)</th>")
          .append("</tr></thead><tbody id='summary_body'>");
        int idx = 0;
        for (var r : results) {
            sb.append("<tr data-index='").append(idx)
              .append("' data-file='").append(escapeHtmlAttr(r.fileName()))
              .append("' data-pixel='").append(String.format("%.2f", r.diffPercent()))
              .append("' data-size='").append(String.format("%.1f", r.sizeDiffPercent()))
              .append("' data-text='").append(formatTextDiffData(r))
              .append("'><td><a href='#diff-").append(idx).append("' onclick='showDiff(").append(idx)
              .append(");return false'>").append(escapeHtml(r.fileName())).append("</a></td>")
              .append("<td class='num'>").append(String.format("%.2f", r.diffPercent())).append("</td>")
              .append("<td class='num'>").append(String.format("%.1f", r.sizeDiffPercent())).append("</td>")
              .append("<td class='num'>").append(escapeHtml(formatTextDiffLabel(r))).append("</td>")
              .append("</tr>");
            idx++;
        }
        sb.append("</tbody></table></div></details>");
    }

    private static void appendTextDiffAccordion(
            StringBuilder sb, File oldDir, File newDir, ImageComparator.Result r) throws IOException {
        TextComparator.TextDiffContent content =
                TextComparator.loadTextDiffContent(oldDir, newDir, r.fileName());
        sb.append("<details class='text-diff'>");
        if (content.available()) {
            sb.append("<summary>テキスト差分（").append(String.format("%.1f%%", content.diffPercent())).append("）</summary>");
            sb.append("<div class='text-diff-body'>");
            if (content.rows().isEmpty()) {
                sb.append("<p class='text-diff-empty'>(空)</p>");
            } else {
                appendHtmlTextDiffTable(sb, content.rows());
            }
            sb.append("</div>");
        } else {
            sb.append("<summary>テキスト差分（—）</summary>");
            sb.append("<div class='text-diff-body'><p class='text-diff-empty'>")
              .append("旧・新フォルダに同名の .txt がありません（")
              .append(escapeHtml(textBaseName(r.fileName())))
              .append(".txt）</p></div>");
        }
        sb.append("</details>");
    }

    /** HTML 用: 一致行は省略し、差分行のみ表示する（ブロック間は省略サマリ行を挿入） */
    private static void appendHtmlTextDiffTable(StringBuilder sb, List<TextComparator.LineDiffRow> rows) {
        int totalLines = rows.size();
        long diffLineCount = rows.stream()
                .filter(r -> r.kind() != TextComparator.LineKind.SAME)
                .count();
        if (diffLineCount == 0) {
            sb.append("<p class='text-diff-empty'>テキストに差分はありません（全 ")
              .append(totalLines)
              .append(" 行一致）</p>");
            return;
        }
        sb.append("<p class='text-diff-stats'>差分 ")
          .append(diffLineCount)
          .append(" 行 / 全 ")
          .append(totalLines)
          .append(" 行（一致行は省略）</p>");
        sb.append("<table class='text-diff-table'><thead><tr><th>旧</th><th>新</th></tr></thead><tbody>");
        int omittedRun = 0;
        for (var row : rows) {
            if (row.kind() == TextComparator.LineKind.SAME) {
                omittedRun++;
                continue;
            }
            if (omittedRun > 0) {
                appendTextDiffOmittedRow(sb, omittedRun);
                omittedRun = 0;
            }
            appendTextDiffRow(sb, row);
        }
        sb.append("</tbody></table>");
    }

    private static void appendTextDiffOmittedRow(StringBuilder sb, int sameLineCount) {
        sb.append("<tr class='omitted'><td colspan='2'>… ")
          .append(sameLineCount)
          .append("行一致（省略）…</td></tr>");
    }

    private static void appendTextDiffRow(StringBuilder sb, TextComparator.LineDiffRow row) {
        String cssClass = switch (row.kind()) {
            case REMOVED -> "removed";
            case ADDED -> "added";
            case CHANGED -> "changed";
            case SAME -> "same";
        };
        sb.append("<tr class='").append(cssClass).append("'>")
          .append("<td class='old'>").append(formatDiffCell(row.oldLine())).append("</td>")
          .append("<td class='new'>").append(formatDiffCell(row.newLine())).append("</td>")
          .append("</tr>");
    }

    private static String formatDiffCell(String line) {
        if (line == null || line.isEmpty()) {
            return "&#160;";
        }
        return escapeHtml(line);
    }

    private static String textBaseName(String imageFileName) {
        int dot = imageFileName.lastIndexOf('.');
        return dot > 0 ? imageFileName.substring(0, dot) : imageFileName;
    }

    private static String escapeHtml(String text) {
        return escapeHtmlAttr(text);
    }

    private static String escapeHtmlAttr(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatSectionTitle(ImageComparator.Result r) {
        return r.fileName()
                + "　比較差異　ピクセル：" + String.format("%.2f%%", r.diffPercent())
                + "　画像サイズ：" + String.format("%.1f%%", r.sizeDiffPercent())
                + "　テキスト：" + formatTextDiffLabel(r);
    }

    static BufferedImage loadReportImage(BufferedImage image, File fallbackFile) throws IOException {
        if (image != null) {
            return image;
        }
        BufferedImage loaded = ImageIO.read(fallbackFile);
        if (loaded == null) {
            throw new IOException("画像を読み込めません: " + fallbackFile.getAbsolutePath());
        }
        return loaded;
    }

    /** PDF 等で HTML と同じ表示用画像を使う */
    static BufferedImage prepareDisplayImage(
            BufferedImage image,
            File fallbackFile,
            int splitHeadHeight,
            int splitTailHeight) throws IOException {
        BufferedImage loaded = image;
        if (loaded == null) {
            loaded = ImageIO.read(fallbackFile);
            if (loaded == null) {
                throw new IOException("画像を読み込めません: " + fallbackFile.getAbsolutePath());
            }
        }
        return ImageDisplaySplitter.apply(loaded, splitHeadHeight, splitTailHeight);
    }

    static BufferedImage prepareDiffDisplayImage(
            BufferedImage diffOverlayImage, int splitHeadHeight, int splitTailHeight) {
        if (diffOverlayImage == null) {
            return null;
        }
        return ImageDisplaySplitter.apply(diffOverlayImage, splitHeadHeight, splitTailHeight);
    }

    static String sectionTitle(ImageComparator.Result r) {
        return formatSectionTitle(r);
    }

    /** PDF 等で比較差異行として使う（ファイル名なし） */
    static String comparisonMetricsLine(ImageComparator.Result r) {
        return "比較差異　ピクセル：" + String.format("%.2f%%", r.diffPercent())
                + "　画像サイズ：" + String.format("%.1f%%", r.sizeDiffPercent())
                + "　テキスト：" + formatTextDiffLabel(r);
    }

    /** テキスト .txt が無い場合は -1（フィルタ対象外） */
    private static String formatTextDiffData(ImageComparator.Result r) {
        return r.textDiffPercent() < 0 ? "-1" : String.format("%.1f", r.textDiffPercent());
    }

    private static String formatTextDiffLabel(ImageComparator.Result r) {
        return r.textDiffPercent() < 0 ? "—" : String.format("%.1f%%", r.textDiffPercent());
    }

    private static String formatTextDiffCsv(double textDiffPercent) {
        return textDiffPercent < 0 ? "" : String.format("%.1f", textDiffPercent);
    }

    private static String toJsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String encodeReportImage(
            BufferedImage image,
            File fallbackFile,
            ImageFormat format,
            float jpegQuality,
            int splitHeadHeight,
            int splitTailHeight) throws IOException {
        if (image != null) {
            BufferedImage display = prepareDisplayImage(image, fallbackFile, splitHeadHeight, splitTailHeight);
            if (format == ImageFormat.JPEG) {
                return "data:image/jpeg;base64," + encodeImageJpeg(display, jpegQuality);
            }
            return toDataUriPng(display);
        }
        return encodeReportFile(fallbackFile, format, jpegQuality, splitHeadHeight, splitTailHeight);
    }

    private static String encodeReportFile(
            File file, ImageFormat format, float jpegQuality, int splitHeadHeight, int splitTailHeight)
            throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            return encodeFileOriginal(file);
        }
        BufferedImage display = ImageDisplaySplitter.apply(img, splitHeadHeight, splitTailHeight);
        if (format == ImageFormat.JPEG) {
            return "data:image/jpeg;base64," + encodeImageJpeg(display, jpegQuality);
        }
        return toDataUriPng(display);
    }

    private static String encodeFileOriginal(File file) throws IOException {
        String mime = mimeTypeForFile(file);
        byte[] bytes = Files.readAllBytes(file.toPath());
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String mimeTypeForFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    private static String toDataUriPng(BufferedImage img) throws IOException {
        return "data:image/png;base64," + encodeImagePng(img);
    }

    private static String encodeImageJpeg(BufferedImage img, float quality) throws IOException {
        // JPEGは透明度非対応なのでRGBに変換
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        }
        writer.dispose();
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String encodeImagePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

}
