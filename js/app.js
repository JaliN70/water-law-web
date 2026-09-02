(function () {
  'use strict';

  const app = document.getElementById('app');
  const backBtn = document.getElementById('backBtn');
  const pageTitle = document.getElementById('pageTitle');
  const pageSub = document.getElementById('pageSub');
  const headerMojLink = document.getElementById('headerMojLink');
  const toast = document.getElementById('toast');

  document.getElementById('sysDate').textContent = META.updated;
  document.getElementById('sysAuthor').textContent = META.author;

  let toastTimer;
  let currentLaw = null;

  function showToast(msg) {
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 2200);
  }

  function isLocalLaw(law) {
    return law && (law.source === 'pdf' || !law.pcode);
  }

  const APPENDIX_SECTIONS = {
    'A-1': '給水系統之計算',
    'A-2': '熱水系統之計算',
    'A-3': '排水通氣系統之計算',
    'A-4': '熱水供給系統、管路配置方式及熱水供應分區',
    'A-5': '工廠製造的油脂截留器之選用計算',
  };

  function normalizeText(s) {
    return String(s ?? '').normalize('NFKC');
  }

  if (typeof PLUMBING_SPEC_LAW !== 'undefined') {
    for (const cat of PLUMBING_SPEC_LAW.categories || []) {
      for (const item of cat.articles || []) {
        if (item.label) item.label = normalizeText(item.label);
        if (item.body) item.body = normalizeText(item.body);
        for (const m of item.media || []) {
          if (m.caption) m.caption = normalizeText(m.caption);
        }
      }
    }
  }

  function appendixSectionTitle(item) {
    if (/^A-[1-5]$/.test(item.art)) return APPENDIX_SECTIONS[item.art] || item.art;
    return null;
  }

  function articleListNum(art, item, cat) {
    if (cat && cat.id === 'PLUMB-appendix') {
      if (/^表-|^圖-/.test(art)) {
        const m = art.match(/^(表|圖)-(.+)$/);
        return m ? `${m[1]} ${m[2]}` : art;
      }
      if (/^A-[1-5]$/.test(art)) return art.replace('A-', '');
    }
    return art;
  }

  function articleListLabel(item, cat) {
    if (cat && cat.id === 'PLUMB-appendix') {
      const section = appendixSectionTitle(item);
      if (section) return section;
      if (/^表-|^圖-/.test(item.art)) return item.label || item.art;
    }
    return item.label;
  }

  function articleListHint(item, cat) {
    if (!cat || cat.id !== 'PLUMB-appendix') return '';
    if (/^A-[1-5]$/.test(item.art)) return '計算章節';
    if (/^表-|^圖-/.test(item.art)) return '詳內文連結';
    return '';
  }

  function articleBtnClass(item, cat) {
    if (cat && cat.id === 'PLUMB-appendix') {
      if (/^表-/.test(item.art)) return 'art-btn art-btn--guide art-btn--table';
      if (/^圖-/.test(item.art)) return 'art-btn art-btn--guide art-btn--figure';
      if (/^A-[1-5]$/.test(item.art)) return 'art-btn art-btn--guide';
    }
    return 'art-btn';
  }

  function formatArticleNum(art, item) {
    if (/^表-|^圖-/.test(art)) {
      const m = art.match(/^(表|圖)-(.+)$/);
      return m ? `${m[1]} ${m[2]}` : art;
    }
    return art;
  }

  function articleDisplayTitle(art, law, item, cat) {
    if (isLocalLaw(law) && item && cat && cat.id === 'PLUMB-appendix') {
      const section = appendixSectionTitle(item);
      if (section) return section;
      if (/^表-|^圖-/.test(art)) return item.label || art;
    }
    return isLocalLaw(law) ? art : `第 ${art} 條`;
  }

  function primaryPdfPage(item) {
    if (item.pages && item.pages.length) return item.pages[0];
    if (item.media && item.media.length) return item.media[0].page;
    return 2;
  }

  function pdfUrl(law, page) {
    if (!law || !law.pdfFile) return 'https://law.moj.gov.tw/';
    const q = new URLSearchParams({ file: law.pdfFile });
    if (law.pageCount) q.set('pages', String(law.pageCount));
    if (law.pageDir) q.set('pageDir', law.pageDir);
    if (page && page > 0) q.set('page', String(page));
    if (location.protocol === 'file:') q.set('mode', 'png');
    return `pdf-view.html?${q.toString()}`;
  }

  function escapeHtml(s) {
    return normalizeText(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function formatBody(text) {
    return escapeHtml(text).replace(/\n\n/g, '</p><p>').replace(/\n/g, '<br>');
  }

  function renderMediaBlock(law, media) {
    const kind = media.type === 'table' ? '表格' : '圖';
    const page = media.page || 2;
    const href = pdfUrl(law, page);
    return `
      <a class="spec-media-link spec-media-link--${escapeHtml(media.type)}"
         href="${escapeHtml(href)}" target="_blank" rel="noopener"
         id="media-${escapeHtml(media.ref)}">
        <span class="spec-media-tag">${kind}</span>
        <span class="spec-media-caption">${escapeHtml(media.caption)}</span>
        <span class="spec-media-action">詳內文連結
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/>
            <polyline points="15 3 21 3 21 9"/>
            <line x1="10" y1="14" x2="21" y2="3"/>
          </svg>
        </span>
      </a>`;
  }

  function isTableOcrParagraph(text, media) {
    if (!media.some((m) => m.type === 'table')) return false;
    if (/^表[\s\d\-A-Za-z]/.test(text)) return false;
    if (/^[\d\s]{3,}$/.test(text)) return true;
    if (/^\d+\s+\d+/.test(text)) return true;
    if (/^(給水栓|衛生設備|水洗馬桶|一般)/.test(text) && text.length < 40) return true;
    return false;
  }

  function renderPlumbingContent(item, law) {
    const media = item.media || [];
    if (/^表-|^圖-/.test(item.art) && media.length) {
      return media.map((m) => renderMediaBlock(law, m)).join('');
    }
    const shownPages = new Set();
    const parts = [];

    if (item.body) {
      const paragraphs = item.body.split('\n\n').filter(Boolean);
      paragraphs.forEach((para) => {
        const trimmed = para.trim();
        if (isTableOcrParagraph(trimmed, media)) return;

        const matched = media.find((m) => trimmed === m.caption || trimmed.includes(m.caption));
        if (matched && matched.type === 'table' && trimmed === matched.caption) {
          if (!shownPages.has(matched.page)) {
            shownPages.add(matched.page);
            parts.push(renderMediaBlock(law, matched));
          }
          return;
        }
        parts.push(`<p>${formatBody(trimmed)}</p>`);
        if (matched && matched.type === 'figure' && !shownPages.has(matched.page)) {
          shownPages.add(matched.page);
          parts.push(renderMediaBlock(law, matched));
        }
      });
    }

    media.forEach((m) => {
      if (!shownPages.has(m.page)) {
        shownPages.add(m.page);
        parts.push(renderMediaBlock(law, m));
      }
    });

    return parts.join('');
  }

  function getLaw(lawId) {
    return LAWS.find((l) => l.id === lawId);
  }

  function getCategory(law, chapterId) {
    return law && law.categories.find((c) => c.id === chapterId);
  }

  function getArticle(law, chapterId, artId) {
    const cat = getCategory(law, chapterId);
    return cat && cat.articles.find((a) => a.art === artId);
  }

  function chapterArticles(cat, law) {
    if (!isLocalLaw(law)) return cat.articles;
    if (cat.id === 'PLUMB-appendix') {
      return cat.articles.filter((item) => {
        if (/^表-|^圖-/.test(item.art)) return true;
        if (/^A-[1-5]$/.test(item.art)) {
          return /之計算|供應分區|選用計算|配置方式及/.test(item.label || '');
        }
        if (/^A-\d+$/.test(item.art)) return false;
        if (/^\d+\.\d+/.test(item.art)) return item.body.trim().length > 20;
        return !/^[\d.\s\-]+$/.test(item.art) && !/^A-\d/.test(item.art);
      });
    }
    return cat.articles.filter((item) => {
      if (!/^\d+\.\d+$/.test(item.art)) return true;
      return item.body.trim().length > 20;
    });
  }

  function parseHash() {
    const raw = location.hash.replace(/^#\/?/, '');
    if (!raw) return [];
    return raw.split('/').map((p) => decodeURIComponent(p)).filter(Boolean);
  }

  function buildHash(parts) {
    if (!parts.length) return '#/';
    return '#/' + parts.map((p) => encodeURIComponent(p)).join('/');
  }

  function navigate(parts) {
    const hash = buildHash(parts);
    if (location.hash !== hash) {
      location.hash = hash;
    } else {
      route();
    }
  }

  function setHeaderLink(law, page) {
    if (!law || isLocalLaw(law)) {
      if (law && law.pdfFile) {
        headerMojLink.href = pdfUrl(law, page);
        headerMojLink.setAttribute('aria-label', page ? `PDF 原文第 ${page} 頁` : '下載 PDF 原文');
      } else {
        headerMojLink.href = 'https://law.moj.gov.tw/';
        headerMojLink.setAttribute('aria-label', '全國法規資料庫');
      }
      return;
    }
    headerMojLink.href = law.fullUrl;
    headerMojLink.setAttribute('aria-label', '全國法規資料庫');
  }

  function lawSourceNote(law) {
    return isLocalLaw(law)
      ? '條文內容收錄於 App 內，PDF 原文可下載參照'
      : '法條內容連結至全國法規資料庫，以司法院最新公布為準';
  }

  function renderHome() {
    currentLaw = null;
    backBtn.hidden = true;
    pageTitle.textContent = '給排水設備法規';
    pageSub.textContent = '自來水 · 下水道 · 設計規範';
    setHeaderLink(null);
    document.getElementById('lawDate').textContent = '—';

    const totalArts = LAWS.reduce(
      (n, law) => n + law.categories.reduce((m, c) => m + chapterArticles(c, law).length, 0),
      0
    );

    const cards = LAWS.map((law, i) => {
      const arts = law.categories.reduce((n, c) => n + chapterArticles(c, law).length, 0);
      const unit = isLocalLaw(law) ? '節' : '條';
      return `
      <li class="fade-up" style="animation-delay:${i * 0.05}s">
        <button type="button" class="cat-card cat-card--chapter" data-law="${escapeHtml(law.id)}" aria-label="${escapeHtml(law.name)}">
          <span class="cat-icon" aria-hidden="true">${law.icon}</span>
          <h2 class="cat-title">${escapeHtml(law.shortName)}</h2>
          <p class="cat-sub">${escapeHtml(law.name)}</p>
          <span class="cat-count">${law.categories.length} 章 · ${arts} ${unit}</span>
        </button>
      </li>`;
    }).join('');

    app.innerHTML = `
      <section class="hero fade-up">
        <span class="hero-eyebrow">Water &amp; Sewer Standards</span>
        <h2 class="hero-title">給排水設備法規</h2>
        <p class="hero-desc">${LAWS.length} 部法規 · 共 ${totalArts} 條，選擇法規後依章節查詢</p>
      </section>
      <p class="section-label">法規選擇</p>
      <ul class="cat-grid cat-grid--chapters">${cards}</ul>
    `;
  }

  function renderLaw(lawId) {
    const law = getLaw(lawId);
    if (!law) {
      navigate([]);
      return;
    }

    currentLaw = law;
    backBtn.hidden = false;
    pageTitle.textContent = law.shortName;
    pageSub.textContent = law.name;
    setHeaderLink(law);
    document.getElementById('lawDate').textContent = law.amended;

    const totalArts = law.categories.reduce((n, c) => n + chapterArticles(c, law).length, 0);
    const unit = isLocalLaw(law) ? '節' : '條';
    const ctaHtml = isLocalLaw(law)
      ? (law.pdfFile
        ? `<a class="hero-cta" href="${escapeHtml(law.pdfFile)}" target="_blank" rel="noopener">
            PDF 原文
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </a>`
        : '')
      : `<a class="hero-cta" href="${law.fullUrl}" target="_blank" rel="noopener">
          完整法規
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
        </a>`;

    const cards = law.categories.map((cat, i) => {
      const count = chapterArticles(cat, law).length;
      return `
      <li class="fade-up" style="animation-delay:${i * 0.04}s">
        <button type="button" class="cat-card cat-card--chapter" data-chapter="${escapeHtml(cat.id)}" aria-label="${escapeHtml(cat.subtitle)}">
          <span class="cat-icon" aria-hidden="true">${cat.icon}</span>
          <h2 class="cat-title">${escapeHtml(cat.title)}</h2>
          <p class="cat-sub">${escapeHtml(cat.subtitle)}</p>
          <span class="cat-count">${count} ${unit}</span>
        </button>
      </li>`;
    }).join('');

    app.innerHTML = `
      <section class="hero fade-up">
        <span class="hero-eyebrow">${escapeHtml(law.shortName)}</span>
        <h2 class="hero-title">${escapeHtml(law.name)}</h2>
        <p class="hero-desc">${law.categories.length} 章 · ${totalArts} ${unit} · 修正 ${escapeHtml(law.amended)}</p>
        ${ctaHtml}
      </section>
      <p class="section-label">章節目錄</p>
      <ul class="cat-grid cat-grid--chapters">${cards}</ul>
    `;
  }

  function renderChapter(lawId, chapterId) {
    const law = getLaw(lawId);
    const cat = getCategory(law, chapterId);
    if (!cat) {
      navigate(lawId ? [lawId] : []);
      return;
    }

    currentLaw = law;
    backBtn.hidden = false;
    pageTitle.textContent = cat.title;
    pageSub.textContent = law.shortName;
    setHeaderLink(law);
    document.getElementById('lawDate').textContent = law.amended;

    const articles = chapterArticles(cat, law);
    const local = isLocalLaw(law);
    const unit = local ? '節' : '條';

    const articlesHtml = articles
      .map(
        (item, i) => {
          const listLabel = articleListLabel(item, cat);
          const listHint = articleListHint(item, cat);
          const btnClass = articleBtnClass(item, cat);
          const isMediaGuide = local && /^表-|^圖-/.test(item.art);
          const mediaPage = isMediaGuide ? primaryPdfPage(item) : 0;
          const inner = `
                <span class="art-num">${escapeHtml(articleListNum(item.art, item, cat))}</span>
                <span class="art-label">${escapeHtml(listLabel)}${listHint ? `<span class="art-note${listHint === '詳內文連結' ? ' art-note--link' : ''}">${escapeHtml(listHint)}</span>` : ''}</span>
                <svg class="art-arrow" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  ${isMediaGuide
                    ? '<path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>'
                    : '<polyline points="9 18 15 12 9 6"/>'}
                </svg>`;
          return `
        <li class="fade-up" style="animation-delay:${Math.min(i, 12) * 0.02}s">
          ${local
            ? isMediaGuide
              ? `<a class="${btnClass}" href="${escapeHtml(pdfUrl(law, mediaPage))}" target="_blank" rel="noopener"
                   data-art="${escapeHtml(item.art)}" aria-label="${escapeHtml(listLabel)} · 詳內文連結">${inner}</a>`
              : `<button type="button" class="${btnClass}" data-art="${escapeHtml(item.art)}" aria-label="${escapeHtml(articleDisplayTitle(item.art, law, item, cat))}">${inner}</button>`
            : `<a class="art-btn" href="${mojArticleUrl(law.pcode, item.art)}" target="_blank" rel="noopener"
                 data-art="${escapeHtml(item.art)}">${inner}</a>`}
        </li>
      `;
        }
      )
      .join('');

    app.innerHTML = `
      <header class="cat-header fade-up">
        <div class="cat-header-icon" aria-hidden="true">${cat.icon}</div>
        <h2 class="cat-header-title">${escapeHtml(cat.subtitle)}</h2>
        <p class="cat-header-sub">${escapeHtml(law.name)}</p>
        <p class="cat-header-desc">${articles.length} ${unit} · ${local ? 'App 內閱讀' : '連結全國法規資料庫'}</p>
      </header>
      <p class="section-label">${local ? '條文目錄' : '法條連結 · 全國法規資料庫'}</p>
      <ul class="art-list">${articlesHtml}</ul>
    `;
  }

  function renderArticle(lawId, chapterId, artId) {
    const law = getLaw(lawId);
    const cat = getCategory(law, chapterId);
    const item = getArticle(law, chapterId, artId);
    if (!item || !isLocalLaw(law)) {
      navigate([lawId, chapterId]);
      return;
    }

    currentLaw = law;
    backBtn.hidden = false;
    const detailTitle = appendixSectionTitle(item)
      || (/^表-|^圖-/.test(item.art) ? (item.label || item.art) : item.art);
    pageTitle.textContent = detailTitle;
    pageSub.textContent = cat.title;
    setHeaderLink(law, primaryPdfPage(item));
    document.getElementById('lawDate').textContent = law.amended;

    app.innerHTML = `
      <article class="art-detail fade-up">
        <header class="art-detail-head">
          <p class="art-detail-breadcrumb">${escapeHtml(law.shortName)} · ${escapeHtml(cat.subtitle)}</p>
          <h2 class="art-detail-title">${escapeHtml(detailTitle)}</h2>
        </header>
        <div class="art-detail-body">${renderPlumbingContent(item, law)}</div>
        ${law.pdfFile
          ? `<a class="hero-cta art-detail-pdf" href="${escapeHtml(pdfUrl(law, primaryPdfPage(item)))}" target="_blank" rel="noopener">
              PDF 原文（第 ${primaryPdfPage(item)} 頁）
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            </a>`
          : ''}
      </article>
    `;
  }

  function route() {
    const parts = parseHash();

    if (parts.length === 0) {
      renderHome();
      return;
    }

    if (parts.length === 1) {
      renderLaw(parts[0]);
      return;
    }

    if (parts.length === 2) {
      renderChapter(parts[0], parts[1]);
      return;
    }

    renderArticle(parts[0], parts[1], parts[2]);
  }

  function goBack() {
    const parts = parseHash();
    if (parts.length >= 3) {
      navigate([parts[0], parts[1]]);
    } else if (parts.length >= 2) {
      navigate([parts[0]]);
    } else if (parts.length === 1) {
      navigate([]);
    }
  }

  app.addEventListener('click', (e) => {
    const lawBtn = e.target.closest('[data-law]');
    if (lawBtn) {
      e.preventDefault();
      navigate([lawBtn.dataset.law]);
      return;
    }

    const chapterBtn = e.target.closest('[data-chapter]');
    if (chapterBtn) {
      e.preventDefault();
      const parts = parseHash();
      const lawId = parts[0] || (currentLaw && currentLaw.id);
      if (lawId) navigate([lawId, chapterBtn.dataset.chapter]);
      return;
    }

    const artBtn = e.target.closest('.art-btn[data-art]');
    if (artBtn && artBtn.tagName === 'BUTTON') {
      e.preventDefault();
      const parts = parseHash();
      if (parts.length >= 2) {
        navigate([parts[0], parts[1], artBtn.dataset.art]);
      }
      return;
    }

    const artLink = e.target.closest('.art-btn');
    if (artLink) {
      showToast(`開啟 ${articleDisplayTitle(artLink.dataset.art, currentLaw)}`);
    }
  });

  backBtn.addEventListener('click', goBack);
  window.addEventListener('hashchange', route);

  route();
})();
