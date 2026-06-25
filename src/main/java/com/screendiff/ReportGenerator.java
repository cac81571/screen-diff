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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public class ReportGenerator {

    public enum ImageFormat {
        PNG,
        JPEG
    }

    /** HTML レポート内の画像の配置方法 */
    public enum HtmlImagePlacement {
        /** HTML 内に Base64 で埋め込み（単一ファイル） */
        INLINE,
        /** report_assets/ フォルダに書き出して参照 */
        EXTERNAL
    }

    public static void writeCsv(List<ImageComparator.Result> results, File output) throws IOException {
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(new FileOutputStream(output), "UTF-8"))) {
            writer.writeNext(new String[]{
                    "ファイル名", "ピクセル差異(%)", "横幅の差異(px)", "高さの差異(px)", "切り取り", "テキスト差異(行)",
                    "旧画像幅", "新画像幅", "旧画像高さ", "新画像高さ"
            });
            for (var r : results) {
                writer.writeNext(new String[]{
                        r.fileName(),
                        String.format("%.2f", r.diffPercent()),
                        String.valueOf(r.widthDiff()),
                        String.valueOf(r.heightDiff()),
                        formatCropSummaryLabel(r),
                        formatTextDiffCsv(r.textDiffLines()),
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
            int cropThreshold,
            int cropAmount,
            boolean trimMargins,
            HtmlImagePlacement imagePlacement,
            TextTransformUtil.TextTransformOptions oldTextTransform,
            TextTransformUtil.TextTransformOptions newTextTransform,
            BooleanSupplier cancelled) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html><html><head><meta charset='UTF-8'><title>Screen Diff Report</title>
                <style>
                *{box-sizing:border-box}
                body{font-family:sans-serif;margin:0;padding-top:var(--header-offset,95px);background:#fff}
                .header{position:fixed;top:0;left:0;right:0;background:#e8eef5;border-bottom:1px solid #b8c4d4;box-shadow:0 1px 3px rgba(0,0,0,.08);padding:10px 20px;z-index:1000;display:flex;align-items:center;gap:15px;flex-wrap:wrap}
                .header h1{margin:0;font-size:16px;white-space:nowrap}
                .header-nav-link{color:#06c;text-decoration:none;font-size:14px;white-space:nowrap}
                .header-nav-link:hover{text-decoration:underline}
                .view-mode{display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap}
                .view-mode label{margin:0;white-space:nowrap;font-weight:normal;font-size:14px}
                .content{padding:20px}
                .diff-section{margin-bottom:16px;scroll-margin-top:var(--header-offset,95px)}
                .diff-section h2{margin:0 0 4px;font-size:16px;font-weight:bold;line-height:1.3}
                .pair{display:flex;gap:10px;width:100%;margin:0}
                .pair>div{flex:1;min-width:0}
                .pair p{margin:0 0 2px;font-size:13px}
                .crop-note{margin:4px 0 0;font-size:12px;color:#555}
                .pair.single-new>div.old-panel{display:none}
                .pair.single-new>div.new-panel{flex:1}
                .pair.single-old>div.new-panel{display:none}
                .pair.single-old>div.old-panel{flex:1}
                .img-wrap{display:flex;justify-content:center;width:100%;overflow:hidden;cursor:zoom-in;border:1px solid #888;background:#fafafa}
                .img-wrap.zoomed{cursor:grab;justify-content:flex-start}
                .img-wrap.zoomed.dragging{cursor:grabbing;user-select:none}
                .img-wrap.zoomed .report-base{image-rendering:pixelated;image-rendering:crisp-edges}
                .img-zoom-inner{position:relative;display:inline-block;max-width:100%;line-height:0;transform-origin:0 0}
                .img-zoom-inner .report-base{position:relative;z-index:0;width:auto;height:auto;max-width:100%;display:block;border:0;vertical-align:top}
                .img-zoom-inner .overlay,.img-zoom-inner .blink-img{position:absolute;top:0;left:0;width:100%;height:100%;display:none;pointer-events:none;object-fit:fill}
                .img-zoom-inner .overlay{opacity:0.5}
                .text-diff{margin-top:8px;border:1px solid #ccc;border-radius:4px;background:#fafafa;scroll-margin-top:var(--header-offset,95px)}
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
                .text-diff-table .diff-inline-old{background:#faa}
                .text-diff-table .diff-inline-new{background:#afa}
                .report-summary{margin-bottom:16px;border:1px solid #b8c4d4;border-radius:4px;background:#fafafa;scroll-margin-top:var(--header-offset,95px)}
                .report-summary>summary{padding:10px 14px;cursor:pointer;font-weight:bold;font-size:15px;user-select:none;list-style:disclosure-closed inside}
                .report-summary[open]>summary{border-bottom:1px solid #ddd;list-style:disclosure-open inside}
                .summary-panel{padding:12px 14px 14px}
                .summary-table{width:100%;border-collapse:collapse;font-size:13px}
                .summary-table th,.summary-table td{border:1px solid #ccc;padding:6px 8px;text-align:left}
                .summary-table th{background:#e8eef5;cursor:pointer;user-select:none;white-space:nowrap}
                .summary-table th:hover{background:#d8e4f0}
                .summary-table td.num{text-align:right;font-variant-numeric:tabular-nums}
                .summary-table tr:hover td{background:#f5f8fc}
                .summary-table a{color:#06c;text-decoration:none}
                .summary-table a:hover{text-decoration:underline}
                @media print{
                body{padding-top:0}
                .header,.report-summary,.text-diff{display:none!important}
                .content{padding:0}
                .diff-section{
                break-before:page;
                page-break-before:always;
                break-inside:avoid;
                page-break-inside:avoid;
                margin-bottom:0;
                scroll-margin-top:0
                }
                .img-wrap{cursor:default;overflow:visible;border-color:#ccc}
                .img-wrap .img-zoom-inner{transform:none!important}
                .img-zoom-inner .overlay,.img-zoom-inner .blink-img{display:none!important}
                }
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
                function goToSummary(){
                  resetAllInlineZoom();
                  var el=document.getElementById('report_summary');
                  if(!el){return;}
                  el.open=true;
                  el.scrollIntoView({behavior:'smooth',block:'start'});
                }
                function goToDiff(index){
                  resetAllInlineZoom();
                  var el=document.getElementById('diff-'+index);
                  if(el){el.scrollIntoView({behavior:'smooth',block:'start'});}
                }
                function showDiff(index){
                  goToDiff(index);
                }
                function showTextDiff(index){
                  resetAllInlineZoom();
                  var section=document.getElementById('diff-'+index);
                  if(!section){return;}
                  var textDiff=section.querySelector('details.text-diff');
                  if(!textDiff){return;}
                  textDiff.open=true;
                  textDiff.scrollIntoView({behavior:'smooth',block:'start'});
                }
                function scrollFromHash(){
                  var hash=location.hash;
                  if(!hash){return;}
                  if(hash==='#report_summary'){
                    goToSummary();
                    return;
                  }
                  if(hash.indexOf('#text-diff-')===0){
                    var textDiff=document.getElementById(hash.substring(1));
                    if(textDiff){
                      var section=textDiff.closest('.diff-section');
                      if(section&&section.dataset.index!=null){
                        showTextDiff(parseInt(section.dataset.index,10));
                      }
                    }
                    return;
                  }
                  if(hash.indexOf('#diff-')!==0){return;}
                  var el=document.querySelector(hash);
                  if(!el||el.dataset.index==null){return;}
                  goToDiff(parseInt(el.dataset.index,10));
                }
                function summaryTextSortKey(row){
                  var t=parseFloat(row.dataset.text);
                  return t<0?-1:t;
                }
                var summarySortColumn=null;
                var summarySortAsc=true;
                function compareSummaryRows(a,b,column,asc){
                  var cmp=0;
                  switch(column){
                    case 'no':
                      cmp=parseInt(a.dataset.index,10)-parseInt(b.dataset.index,10);
                      break;
                    case 'name':
                      cmp=(a.dataset.file||'').localeCompare(b.dataset.file||'','ja');
                      break;
                    case 'oldsize':
                      cmp=parseInt(a.dataset.oldw,10)-parseInt(b.dataset.oldw,10);
                      if(cmp===0){cmp=parseInt(a.dataset.oldh,10)-parseInt(b.dataset.oldh,10);}
                      break;
                    case 'newsize':
                      cmp=parseInt(a.dataset.neww,10)-parseInt(b.dataset.neww,10);
                      if(cmp===0){cmp=parseInt(a.dataset.newh,10)-parseInt(b.dataset.newh,10);}
                      break;
                    case 'pixel':
                      cmp=parseFloat(a.dataset.pixel)-parseFloat(b.dataset.pixel);
                      break;
                    case 'widthdiff':
                      cmp=parseInt(a.dataset.widthdiff,10)-parseInt(b.dataset.widthdiff,10);
                      break;
                    case 'heightdiff':
                      cmp=parseInt(a.dataset.heightdiff,10)-parseInt(b.dataset.heightdiff,10);
                      break;
                    case 'text':
                      cmp=summaryTextSortKey(a)-summaryTextSortKey(b);
                      break;
                    default:
                      cmp=parseInt(a.dataset.index,10)-parseInt(b.dataset.index,10);
                  }
                  return asc?cmp:-cmp;
                }
                function updateSummarySortHeaders(){
                  document.querySelectorAll('.summary-table th[data-sort]').forEach(function(th){
                    var col=th.dataset.sort;
                    var base=th.dataset.label;
                    if(col===summarySortColumn){
                      th.textContent=base+(summarySortAsc?' ▲':' ▼');
                    }else{
                      th.textContent=base;
                    }
                  });
                }
                function toggleSummarySort(column){
                  if(summarySortColumn===column){
                    summarySortAsc=!summarySortAsc;
                  }else{
                    summarySortColumn=column;
                    summarySortAsc=(column==='name'||column==='no');
                  }
                  var tbody=document.getElementById('summary_body');
                  var rows=Array.from(tbody.querySelectorAll('tr'));
                  rows.sort(function(a,b){
                    return compareSummaryRows(a,b,summarySortColumn,summarySortAsc);
                  });
                  rows.forEach(function(r){tbody.appendChild(r);});
                  updateSummarySortHeaders();
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
                  if(e.altKey||e.metaKey||!e.ctrlKey){return;}
                  e.preventDefault();
                  adjustSliderByKeyboard(el,e.key==='ArrowRight'?1:-1,null);
                });
                var globalImageZoom=1;
                function setImageZoomAll(){
                  var val=parseFloat(document.getElementById('image_zoom_all').value);
                  document.getElementById('image_zoom_val_all').textContent=Math.round(val)+'%';
                  globalImageZoom=val/100;
                  document.querySelectorAll('.img-wrap').forEach(applySliderZoomToWrap);
                }
                function applySliderZoomToWrap(wrap){
                  if(wrap.dataset.focalZoom==='1'){return;}
                  if(globalImageZoom===1){
                    clearWrapZoomState(wrap);
                    return;
                  }
                  wrap.dataset.ratioX='0.5';
                  wrap.dataset.ratioY='0.5';
                  wrap.dataset.panX='0';
                  wrap.dataset.panY='0';
                  wrap.dataset.scale=String(globalImageZoom);
                  wrap.classList.add('zoomed');
                  updateWrapTransform(wrap);
                }
                function resetImageZoomAll(){
                  globalImageZoom=1;
                  var slider=document.getElementById('image_zoom_all');
                  if(slider){slider.value='100';}
                  var label=document.getElementById('image_zoom_val_all');
                  if(label){label.textContent='100%';}
                  document.querySelectorAll('.img-wrap').forEach(clearWrapZoomState);
                }
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
                  var base=wrap.querySelector('.img-zoom-inner .report-base');
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
                  wrap.dataset.focalZoom='1';
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
                  delete wrap.dataset.focalZoom;
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
                  window.addEventListener('resize',function(){
                    document.querySelectorAll('.img-wrap.zoomed').forEach(updateWrapTransform);
                  });
                }
                function resetInlineZoom(wrap){
                  getSyncedWraps(wrap).forEach(function(w){
                    delete w.dataset.focalZoom;
                    if(globalImageZoom!==1){
                      applySliderZoomToWrap(w);
                    }else{
                      clearWrapZoomState(w);
                    }
                  });
                }
                function resetAllInlineZoom(){
                  document.querySelectorAll('.img-wrap').forEach(function(w){
                    delete w.dataset.focalZoom;
                    if(globalImageZoom!==1){
                      applySliderZoomToWrap(w);
                    }else{
                      clearWrapZoomState(w);
                    }
                  });
                }
                function zoomInlineAtClick(wrap,e){
                  if(wrap.dataset.focalZoom==='1'){
                    resetInlineZoom(wrap);
                    return;
                  }
                  var inner=wrap.querySelector('.img-zoom-inner');
                  var base=inner?inner.querySelector('.report-base'):null;
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
                  if(e.key==='Escape'){resetImageZoomAll();}
                });
                </script>
                </head><body>
                <div class='header' id='report_header'>
                <h1>画面比較レポート</h1>
                <a class='header-nav-link' href='#report_summary' onclick='goToSummary();return false'>差分一覧サマリ</a>
                <div class='view-mode'>
                <span>表示方法</span>
                <label><input type='radio' name='view_mode' value='dual' checked onchange="setViewMode(this.value)"> 両方</label>
                <label><input type='radio' name='view_mode' value='single-old' onchange="setViewMode(this.value)"> 旧のみ</label>
                <label><input type='radio' name='view_mode' value='single-new' onchange="setViewMode(this.value)"> 新のみ</label>
                </div>
                <label>拡大率:<input id='image_zoom_all' type='range' min='25' max='200' value='100' step='5' oninput="setImageZoomAll()"><span id='image_zoom_val_all'>100%</span></label>
                <label><input type='checkbox' onchange="toggleDiffOverlayAll(this.checked)"> 差分表示</label>
                <label><input type='checkbox' onchange="toggleImgOverlayAll(this.checked)"> 新旧表示</label>
                <label>透明度:<input id='opacity_all' type='range' min='0' max='100' value='50' oninput="setOpacityAll()"><span id='opacity_val_all'>50%</span></label>
                <label><input id='blink_cb_all' type='checkbox' onchange="toggleBlinkAll(this.checked)"> 交互表示</label>
                <label>速度:<input id='blink_speed_all' type='range' min='10' max='500' value='250' oninput="setBlinkSpeedAll()"><span id='blink_speed_val_all'>250ms</span></label>
                </div>
                <div class='content'>
                """);
        appendHtmlSummaryAccordion(sb, results);
        sb.append("<div id='report_sections'>");

        Path assetsDir = imagePlacement == HtmlImagePlacement.EXTERNAL
                ? prepareAssetsDir(output)
                : null;
        var resultList = new ArrayList<>(results);
        int idx = 0;
        for (int i = 0; i < resultList.size(); i++) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                for (int j = i; j < resultList.size(); j++) {
                    resultList.set(j, ImageComparator.releaseImages(resultList.get(j)));
                }
                throw new InterruptedIOException("中断されました");
            }
            ImageComparator.Result r = resultList.get(i);
            HtmlAssetUrls urls = buildHtmlImageUrls(
                    idx, r, oldDir, newDir, imagePlacement, assetsDir, imageFormat, jpegQuality,
                    cropThreshold, cropAmount, trimMargins);
            resultList.set(i, ImageComparator.releaseImages(r));
            r = resultList.get(i);

            String diffOldId = "diff_old_" + idx;
            String diffNewId = "diff_new_" + idx;
            String imgOldOnNewId = "img_old_on_new_" + idx;
            String imgNewOnOldId = "img_new_on_old_" + idx;

            sb.append("<div class='diff-section' id='diff-").append(idx)
              .append("' data-index='").append(idx)
              .append("' data-file='").append(escapeHtmlAttr(r.fileName()))
              .append("' data-pixel='").append(String.format("%.2f", r.diffPercent()))
              .append("' data-widthdiff='").append(r.widthDiff())
              .append("' data-heightdiff='").append(r.heightDiff())
              .append("' data-text='").append(formatTextDiffData(r)).append("'>")
              .append("<h2>").append(formatSectionTitle(r)).append("</h2>")
              .append("<div class='pair'>")
              .append("<div class='old-panel'><p>旧</p><div class='img-wrap'><div class='img-zoom-inner'><img class='report-base' id='base_old_")
              .append(idx).append("' src='").append(escapeHtmlAttr(urls.oldUrl())).append("'>")
              .append("<img id='").append(diffOldId).append("' class='overlay' src='")
              .append(escapeHtmlAttr(urls.diffUrl())).append("'>")
              .append("<img id='").append(imgNewOnOldId).append("' class='overlay' src='")
              .append(escapeHtmlAttr(urls.newUrl())).append("'>")
              .append("<img id='blink_new_on_old_").append(idx).append("' class='blink-img' src='")
              .append(escapeHtmlAttr(urls.newUrl())).append("'>")
              .append("</div></div>");
            appendCropNoteIfNeeded(sb, r.oldCropped());
            sb.append("</div>")
              .append("<div class='new-panel'><p>新</p><div class='img-wrap'><div class='img-zoom-inner'><img class='report-base' id='base_new_")
              .append(idx).append("' src='").append(escapeHtmlAttr(urls.newUrl())).append("'>")
              .append("<img id='").append(diffNewId).append("' class='overlay' src='")
              .append(escapeHtmlAttr(urls.diffUrl())).append("'>")
              .append("<img id='").append(imgOldOnNewId).append("' class='overlay' src='")
              .append(escapeHtmlAttr(urls.oldUrl())).append("'>")
              .append("<img id='blink_old_on_new_").append(idx).append("' class='blink-img' src='")
              .append(escapeHtmlAttr(urls.oldUrl())).append("'>")
              .append("</div></div>");
            appendCropNoteIfNeeded(sb, r.newCropped());
            sb.append("</div>")
              .append("</div>");
            appendTextDiffAccordion(sb, idx, oldDir, newDir, r, oldTextTransform, newTextTransform);
            sb.append("</div>");
            idx++;
        }
        sb.append("""
                </div>
                <script>
                totalImages=%d;
                initImageZoom();
                initHeaderOffset();
                scrollFromHash();
                window.addEventListener('hashchange',scrollFromHash);
                </script>
                """.formatted(idx));
        sb.append("</div></body></html>");
        Files.writeString(output.toPath(), sb.toString());
    }

    private record HtmlAssetUrls(String oldUrl, String newUrl, String diffUrl) {}

    private static final String HTML_ASSETS_DIR = "report_assets";

    private static Path prepareAssetsDir(File htmlFile) throws IOException {
        Path dir = htmlFile.toPath().getParent().resolve(HTML_ASSETS_DIR);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.list(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(dir);
        return dir;
    }

    private static String assetRelUrl(String fileName) {
        return HTML_ASSETS_DIR + "/" + fileName;
    }

    private static String assetFileExt(ImageFormat format) {
        return format == ImageFormat.JPEG ? "jpg" : "png";
    }

    private static HtmlAssetUrls buildHtmlImageUrls(
            int idx,
            ImageComparator.Result r,
            File oldDir,
            File newDir,
            HtmlImagePlacement placement,
            Path assetsDir,
            ImageFormat format,
            float jpegQuality,
            int cropThreshold,
            int cropAmount,
            boolean trimMargins) throws IOException {
        BufferedImage overlay = r.diffOverlayImage();
        if (overlay == null) {
            throw new IOException("差分オーバーレイがありません: " + r.fileName());
        }
        int canvasW = overlay.getWidth();
        int canvasH = overlay.getHeight();

        if (placement == HtmlImagePlacement.INLINE) {
            String oldUrl = encodeReportImageToDataUrl(
                    r.reportOldImage(), ImageScanUtil.resolve(oldDir, r.fileName()),
                    format, jpegQuality, cropThreshold, cropAmount, trimMargins, canvasW, canvasH);
            String newUrl = encodeReportImageToDataUrl(
                    r.reportNewImage(), ImageScanUtil.resolve(newDir, r.fileName()),
                    format, jpegQuality, cropThreshold, cropAmount, trimMargins, canvasW, canvasH);
            String diffUrl = encodeDiffOverlayToDataUrl(r);
            return new HtmlAssetUrls(oldUrl, newUrl, diffUrl);
        }

        String ext = assetFileExt(format);
        String oldFile = String.format("%03d_old.%s", idx, ext);
        String newFile = String.format("%03d_new.%s", idx, ext);
        String diffFile = String.format("%03d_diff.png", idx);

        writeReportImageAsset(
                r.reportOldImage(), ImageScanUtil.resolve(oldDir, r.fileName()), assetsDir.resolve(oldFile),
                format, jpegQuality, cropThreshold, cropAmount, trimMargins, canvasW, canvasH);
        writeReportImageAsset(
                r.reportNewImage(), ImageScanUtil.resolve(newDir, r.fileName()), assetsDir.resolve(newFile),
                format, jpegQuality, cropThreshold, cropAmount, trimMargins, canvasW, canvasH);
        writeAssetDiffOverlay(r, assetsDir.resolve(diffFile));

        return new HtmlAssetUrls(assetRelUrl(oldFile), assetRelUrl(newFile), assetRelUrl(diffFile));
    }

    private static String encodeReportImageToDataUrl(
            BufferedImage reportImage,
            File fallbackSource,
            ImageFormat format,
            float jpegQuality,
            int cropThreshold,
            int cropAmount,
            boolean trimMargins,
            int canvasW,
            int canvasH) throws IOException {
        BufferedImage img = reportImage != null
                ? reportImage
                : ImageComparator.loadForReportCanvas(
                        fallbackSource, trimMargins, cropThreshold, cropAmount, canvasW, canvasH);
        try {
            return encodeImageToDataUrl(img, format, jpegQuality);
        } finally {
            if (reportImage == null) {
                ImageScaleUtil.dispose(img);
            }
        }
    }

    private static void writeReportImageAsset(
            BufferedImage reportImage,
            File fallbackSource,
            Path dest,
            ImageFormat format,
            float jpegQuality,
            int cropThreshold,
            int cropAmount,
            boolean trimMargins,
            int canvasW,
            int canvasH) throws IOException {
        BufferedImage img = reportImage != null
                ? reportImage
                : ImageComparator.loadForReportCanvas(
                        fallbackSource, trimMargins, cropThreshold, cropAmount, canvasW, canvasH);
        writeImageToFile(img, dest, format, jpegQuality);
        if (reportImage == null) {
            ImageScaleUtil.dispose(img);
        }
    }

    private static String encodeSourceToDataUrl(
            File source,
            ImageFormat format,
            float jpegQuality,
            int cropThreshold,
            int cropAmount,
            boolean trimMargins,
            int canvasW,
            int canvasH) throws IOException {
        BufferedImage img = ImageComparator.loadForReportCanvas(
                source, trimMargins, cropThreshold, cropAmount, canvasW, canvasH);
        try {
            return encodeImageToDataUrl(img, format, jpegQuality);
        } finally {
            ImageScaleUtil.dispose(img);
        }
    }

    private static String encodeDiffOverlayToDataUrl(ImageComparator.Result r) throws IOException {
        BufferedImage overlay = r.diffOverlayImage();
        if (overlay == null) {
            throw new IOException("差分オーバーレイがありません: " + r.fileName());
        }
        return encodeImageToDataUrl(overlay, ImageFormat.PNG, 1.0f);
    }

    private static String encodeImageToDataUrl(BufferedImage img, ImageFormat format, float jpegQuality)
            throws IOException {
        byte[] bytes = encodeImageBytes(img, format, jpegQuality);
        String mime = format == ImageFormat.JPEG ? "image/jpeg" : "image/png";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] encodeImageBytes(BufferedImage img, ImageFormat format, float jpegQuality)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (format == ImageFormat.JPEG) {
            writeJpegToStream(img, out, jpegQuality);
        } else {
            ImageIO.write(img, "png", out);
        }
        return out.toByteArray();
    }

    private static void writeAssetFromSource(
            File source,
            Path dest,
            ImageFormat format,
            float jpegQuality,
            int cropThreshold,
            int cropAmount,
            boolean trimMargins,
            int canvasW,
            int canvasH) throws IOException {
        BufferedImage img = ImageComparator.loadForReportCanvas(
                source, trimMargins, cropThreshold, cropAmount, canvasW, canvasH);
        writeImageToFile(img, dest, format, jpegQuality);
        ImageScaleUtil.dispose(img);
    }

    private static void writeAssetDiffOverlay(ImageComparator.Result r, Path dest) throws IOException {
        BufferedImage overlay = r.diffOverlayImage();
        if (overlay == null) {
            return;
        }
        ImageIO.write(overlay, "png", dest.toFile());
    }

    private static void writeImageToFile(
            BufferedImage img, Path dest, ImageFormat format, float jpegQuality) throws IOException {
        if (format == ImageFormat.JPEG) {
            try (OutputStream out = Files.newOutputStream(dest)) {
                writeJpegToStream(img, out, jpegQuality);
            }
        } else {
            ImageIO.write(img, "png", dest.toFile());
        }
    }

    private static void writeJpegToStream(BufferedImage img, OutputStream out, float quality) throws IOException {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        if (rgb != img) {
            ImageScaleUtil.dispose(rgb);
        }
    }

    private static void appendHtmlSummaryAccordion(StringBuilder sb, List<ImageComparator.Result> results) {
        sb.append("<details class='report-summary' id='report_summary' open>")
          .append("<summary>差分一覧サマリ（").append(results.size()).append("件）</summary>")
          .append("<div class='summary-panel'>")
          .append("<table class='summary-table'><thead><tr>")
          .append("<th data-sort='no' data-label='No.' onclick=\"toggleSummarySort('no')\">No.</th>")
          .append("<th data-sort='name' data-label='ファイル名' onclick=\"toggleSummarySort('name')\">ファイル名</th>")
          .append("<th data-sort='oldsize' data-label='旧（横×縦）' onclick=\"toggleSummarySort('oldsize')\">旧（横×縦）</th>")
          .append("<th data-sort='newsize' data-label='新（横×縦）' onclick=\"toggleSummarySort('newsize')\">新（横×縦）</th>")
          .append("<th data-sort='pixel' data-label='ピクセル差異(%)' onclick=\"toggleSummarySort('pixel')\">ピクセル差異(%)</th>")
          .append("<th data-sort='widthdiff' data-label='横幅の差異(px)' onclick=\"toggleSummarySort('widthdiff')\">横幅の差異(px)</th>")
          .append("<th data-sort='heightdiff' data-label='高さの差異(px)' onclick=\"toggleSummarySort('heightdiff')\">高さの差異(px)</th>")
          .append("<th data-label='切り取り'>切り取り</th>")
          .append("<th data-sort='text' data-label='テキスト差異(行)' onclick=\"toggleSummarySort('text')\">テキスト差異(行)</th>")
          .append("</tr></thead><tbody id='summary_body'>");
        int idx = 0;
        for (var r : results) {
            sb.append("<tr data-index='").append(idx)
              .append("' data-file='").append(escapeHtmlAttr(r.fileName()))
              .append("' data-oldw='").append(r.oldWidth())
              .append("' data-oldh='").append(r.oldHeight())
              .append("' data-neww='").append(r.newWidth())
              .append("' data-newh='").append(r.newHeight())
              .append("' data-pixel='").append(String.format("%.2f", r.diffPercent()))
              .append("' data-widthdiff='").append(r.widthDiff())
              .append("' data-heightdiff='").append(r.heightDiff())
              .append("' data-text='").append(formatTextDiffData(r))
              .append("'><td class='num'>").append(idx + 1).append("</td>")
              .append("<td><a href='#diff-").append(idx).append("' onclick='showDiff(").append(idx)
              .append(");return false'>").append(escapeHtml(r.fileName())).append("</a></td>")
              .append("<td class='num'>").append(formatImageDimensions(r.oldWidth(), r.oldHeight())).append("</td>")
              .append("<td class='num'>").append(formatImageDimensions(r.newWidth(), r.newHeight())).append("</td>")
              .append("<td class='num'>").append(String.format("%.2f", r.diffPercent())).append("</td>")
              .append("<td class='num'>").append(r.widthDiff()).append("</td>")
              .append("<td class='num'>").append(r.heightDiff()).append("</td>")
              .append("<td>").append(escapeHtml(formatCropSummaryLabel(r))).append("</td>")
              .append(appendSummaryTextDiffCell(idx, r))
              .append("</tr>");
            idx++;
        }
        sb.append("</tbody></table></div></details>");
    }

    private static void appendTextDiffAccordion(
            StringBuilder sb,
            int idx,
            File oldDir,
            File newDir,
            ImageComparator.Result r,
            TextTransformUtil.TextTransformOptions oldTextTransform,
            TextTransformUtil.TextTransformOptions newTextTransform) throws IOException {
        TextComparator.TextDiffContent content = TextComparator.loadTextDiffContentForMembers(
                oldDir, newDir, r.oldMemberPaths(), r.newMemberPaths(), oldTextTransform, newTextTransform);
        String expectedTextNames = formatExpectedTextNames(r);
        sb.append("<details class='text-diff' id='text-diff-").append(idx).append("'>");
        if (content.available()) {
            int displayRows = TextComparator.countDiffDisplayRows(content.rows());
            sb.append("<summary>テキスト差分（").append(escapeHtml(expectedTextNames))
              .append(" … 差分行数 ").append(displayRows).append("行）</summary>");
            sb.append("<div class='text-diff-body'>");
            if (content.rows().isEmpty()) {
                sb.append("<p class='text-diff-empty'>(空)</p>");
            } else {
                appendHtmlTextDiffTable(sb, content.rows());
            }
            sb.append("</div>");
        } else {
            sb.append("<summary>テキスト差分（").append(escapeHtml(expectedTextNames)).append(" … —）</summary>");
            sb.append("<div class='text-diff-body'><p class='text-diff-empty'>")
              .append("旧・新フォルダに .txt がありません（")
              .append(escapeHtml(expectedTextNames))
              .append("）</p></div>");
        }
        sb.append("</details>");
    }

    private static String formatExpectedTextNames(ImageComparator.Result r) {
        int pairCount = Math.min(r.oldMemberPaths().size(), r.newMemberPaths().size());
        if (pairCount == 0) {
            return "—";
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (int i = 0; i < pairCount; i++) {
            names.add(ImageTextGroupUtil.textBaseNameForImage(r.oldMemberPaths().get(i)) + ".txt");
        }
        return String.join(", ", names);
    }

    /** HTML 用: 一致行は省略し、差分行のみ表示する（ブロック間は省略サマリ行を挿入） */
    private static void appendHtmlTextDiffTable(StringBuilder sb, List<TextComparator.LineDiffRow> rows) {
        int totalLines = rows.size();
        int diffDisplayRows = TextComparator.countDiffDisplayRows(rows);
        if (diffDisplayRows == 0) {
            sb.append("<p class='text-diff-empty'>テキストに差分はありません（全 ")
              .append(totalLines)
              .append(" 行一致）</p>");
            return;
        }
        sb.append("<p class='text-diff-stats'>差分行数 ")
          .append(diffDisplayRows)
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
          .append("<td class='old'>").append(formatDiffCellHtml(row.oldLine())).append("</td>")
          .append("<td class='new'>").append(formatDiffCellHtml(row.newLine())).append("</td>")
          .append("</tr>");
    }

    /** java-diff-utils が生成した HTML をそのままセルに埋め込む */
    private static String formatDiffCellHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "&#160;";
        }
        return html;
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
                + "　横幅：" + r.widthDiff() + "px"
                + "　高さ：" + r.heightDiff() + "px"
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
        return ImageScaleUtil.limitForComparison(loaded);
    }

    /** テキスト .txt が無い場合は -1（フィルタ対象外） */
    private static String formatTextDiffData(ImageComparator.Result r) {
        return r.textDiffLines() < 0 ? "-1" : String.valueOf(r.textDiffLines());
    }

    private static String formatTextDiffLabel(ImageComparator.Result r) {
        return r.textDiffLines() < 0 ? "—" : r.textDiffLines() + "行";
    }

    private static String formatCropSummaryLabel(ImageComparator.Result r) {
        if (!r.oldCropped() && !r.newCropped()) {
            return "—";
        }
        if (r.oldCropped() && r.newCropped()) {
            return "旧・新";
        }
        return r.oldCropped() ? "旧" : "新";
    }

    private static void appendCropNoteIfNeeded(StringBuilder sb, boolean cropped) {
        if (cropped) {
            sb.append("<p class='crop-note'>切り取り</p>");
        }
    }

    private static String appendSummaryTextDiffCell(int idx, ImageComparator.Result r) {
        StringBuilder cell = new StringBuilder("<td class='num'>");
        if (r.textDiffLines() < 0) {
            cell.append(escapeHtml("—"));
        } else {
            String label = formatTextDiffLabel(r);
            cell.append("<a href='#text-diff-").append(idx).append("' onclick='showTextDiff(")
                .append(idx).append(");return false'>")
                .append(escapeHtml(label))
                .append("</a>");
        }
        cell.append("</td>");
        return cell.toString();
    }

    private static String formatTextDiffCsv(int textDiffLines) {
        return textDiffLines < 0 ? "" : String.valueOf(textDiffLines);
    }

    /** サマリ表用 … 横×縦（幅×高さ） */
    private static String formatImageDimensions(int width, int height) {
        return width + "×" + height;
    }

}
