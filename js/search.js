(function () {
  'use strict';

  function normalizeText(s) {
    return String(s ?? '').normalize('NFKC').toLowerCase().trim();
  }

  function escapeHtml(s) {
    return String(s ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function truncate(s, max) {
    const t = String(s ?? '').trim();
    return t.length > max ? `${t.slice(0, max)}…` : t;
  }

  function buildHash(parts) {
    if (!parts.length) return '#/';
    return `#/${parts.map((p) => encodeURIComponent(p)).join('/')}`;
  }

  function isLocalLaw(law) {
    return law && (law.source === 'pdf' || !law.pcode);
  }

  function chapterArticles(cat, law) {
    if (!isLocalLaw(law)) return cat.articles || [];
    if (cat.id === 'PLUMB-appendix') {
      return (cat.articles || []).filter((item) => {
        if (/^表-|^圖-/.test(item.art)) return true;
        if (/^A-[1-5]$/.test(item.art)) {
          return /之計算|供應分區|選用計算|配置方式及/.test(item.label || '');
        }
        if (/^A-\d+$/.test(item.art)) return false;
        if (/^\d+\.\d+/.test(item.art)) return (item.body || '').trim().length > 20;
        return !/^[\d.\s\-]+$/.test(item.art) && !/^A-\d/.test(item.art);
      });
    }
    return (cat.articles || []).filter((item) => {
      if (!/^\d+\.\d+$/.test(item.art)) return true;
      return (item.body || '').trim().length > 20;
    });
  }

  function primaryPdfPage(item) {
    const media = item.media || [];
    const fig = media.find((m) => m.type === 'figure' && m.page > 0);
    if (fig) return fig.page;
    const tbl = media.find((m) => m.type === 'table' && m.page > 0);
    if (tbl) return tbl.page;
    const mediaPages = media.map((m) => m.page).filter((p) => p > 0);
    if (mediaPages.length) return Math.max(...mediaPages);
    if (item.pages && item.pages.length) return Math.max(...item.pages);
    return 2;
  }

  function pdfUrl(law, page) {
    const q = new URLSearchParams({ file: law.pdfFile });
    if (law.pageCount) q.set('pages', String(law.pageCount));
    if (law.pageDir) q.set('pageDir', law.pageDir);
    if (page && page > 0) q.set('page', String(page));
    if (location.protocol === 'file:') q.set('mode', 'png');
    return `pdf-view.html?${q.toString()}`;
  }

  function makeItem(fields) {
    const title = fields.title || '';
    const meta = fields.meta || '';
    const detail = fields.detail || '';
    const extra = fields.extra || '';
    return {
      title,
      meta,
      detail: truncate(detail, 120),
      text: normalizeText(`${title} ${meta} ${detail} ${extra}`),
      titleNorm: normalizeText(title),
      metaNorm: normalizeText(meta),
      detailNorm: normalizeText(detail),
      action: fields.action,
      hash: fields.hash,
      url: fields.url,
      href: fields.href,
    };
  }

  function buildIndex() {
    const items = [];

    LAWS.forEach((law) => {
      items.push(makeItem({
        title: law.shortName,
        meta: law.name,
        detail: law.amended,
        action: 'hash',
        hash: buildHash([law.id]),
      }));

      law.categories.forEach((cat) => {
        items.push(makeItem({
          title: cat.title,
          meta: `${law.shortName} · ${cat.subtitle}`,
          detail: cat.desc,
          action: 'hash',
          hash: buildHash([law.id, cat.id]),
        }));

        chapterArticles(cat, law).forEach((item) => {
          const local = isLocalLaw(law);
          const isMediaGuide = local && /^表-|^圖-/.test(item.art);
          const mediaText = (item.media || []).map((m) => m.caption || '').join(' ');
          const base = {
            title: local ? `${item.art} · ${item.label || ''}` : `第 ${item.art} 條 · ${item.label}`,
            meta: `${law.shortName} · ${cat.title}`,
            detail: local ? (item.body || item.label || '') : item.label,
            extra: `${item.art} ${mediaText}`,
          };

          if (!local) {
            items.push(makeItem({ ...base, action: 'url', url: mojArticleUrl(law.pcode, item.art) }));
            return;
          }

          if (isMediaGuide) {
            items.push(makeItem({ ...base, action: 'href', href: pdfUrl(law, primaryPdfPage(item)) }));
            return;
          }

          items.push(makeItem({
            ...base,
            action: 'hash',
            hash: buildHash([law.id, cat.id, item.art]),
          }));
        });
      });
    });

    return items;
  }

  function openItem(item) {
    if (item.action === 'hash' && item.hash) {
      location.hash = item.hash;
      return;
    }
    if (item.action === 'url' && item.url) {
      if (window.Android && typeof Android.openExternal === 'function') {
        Android.openExternal(item.url);
      } else if (location.protocol === 'file:') {
        location.href = item.url;
      } else {
        window.open(item.url, '_blank', 'noopener');
      }
      return;
    }
    if (item.action === 'href' && item.href) {
      location.href = item.href;
    }
  }

  function initSearch(index) {
    const input = document.getElementById('siteSearch');
    const panel = document.getElementById('searchPanel');
    const resultsEl = document.getElementById('searchResults');
    const emptyEl = document.getElementById('searchEmpty');
    if (!input || !panel || !resultsEl) return;

    let timer;

    function hidePanel() {
      panel.hidden = true;
      input.setAttribute('aria-expanded', 'false');
    }

    function showPanel() {
      panel.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    }

    function scoreItem(item, tokens) {
      let score = 0;
      for (const token of tokens) {
        if (!item.text.includes(token)) return -1;
        if (item.titleNorm.includes(token)) score += 4;
        if (item.metaNorm.includes(token)) score += 2;
        if (item.detailNorm.includes(token)) score += 1;
        score += 1;
      }
      return score;
    }

    function render(query) {
      const q = normalizeText(query);
      if (!q) {
        hidePanel();
        resultsEl.innerHTML = '';
        emptyEl.hidden = true;
        return;
      }

      const tokens = q.split(/\s+/).filter(Boolean);
      const matches = index
        .map((item) => ({ item, score: scoreItem(item, tokens) }))
        .filter((x) => x.score >= 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, 40)
        .map((x) => x.item);

      showPanel();
      if (!matches.length) {
        resultsEl.innerHTML = '';
        emptyEl.hidden = false;
        return;
      }

      emptyEl.hidden = true;
      resultsEl.innerHTML = matches.map((item) => `
        <li>
          <button type="button" class="search-result" role="option">
            <span class="search-result-title">${escapeHtml(item.title)}</span>
            <span class="search-result-meta">${escapeHtml(item.meta)}</span>
            ${item.detail ? `<span class="search-result-detail">${escapeHtml(item.detail)}</span>` : ''}
          </button>
        </li>
      `).join('');

      resultsEl.querySelectorAll('.search-result').forEach((btn, i) => {
        btn.addEventListener('click', () => {
          openItem(matches[i]);
          input.value = '';
          hidePanel();
        });
      });
    }

    input.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(() => render(input.value), 120);
    });

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        input.value = '';
        hidePanel();
        input.blur();
      }
    });

    document.addEventListener('click', (e) => {
      if (e.target.closest('.header-search') || e.target.closest('.search-panel')) return;
      hidePanel();
    });
  }

  initSearch(buildIndex());
})();
