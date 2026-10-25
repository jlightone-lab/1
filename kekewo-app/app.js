// 케케우 뉴스 앱 — kekewo.net 글을 WordPress.com 공개 API로 불러와 보여 준다.
'use strict';

const SITE_ID = 219683211;
const API = `https://public-api.wordpress.com/wp/v2/sites/${SITE_ID}`;
const PER_PAGE = 12;

// 사이트 카테고리 (글 수가 많은 순)
const CATEGORIES = [
  { id: 1307289, slug: 'issue', name: '이슈' },
  { id: 13857601, slug: 'politics', name: '시사' },
  { id: 13857603, slug: 'entertainment', name: '연예' },
  { id: 13858325, slug: 'it', name: 'IT' },
  { id: 13858352, slug: 'economy', name: '경제' },
  { id: 13858223, slug: 'tip', name: 'Tip' },
  { id: 13858354, slug: 'movie', name: '영화·드라마' },
  { id: 13857602, slug: 'sports', name: '스포츠' },
  { id: 13859267, slug: 'game', name: '게임' },
  { id: 13858353, slug: 'realestate', name: '부동산' },
];
const CATEGORY_BY_ID = new Map(CATEGORIES.map(c => [c.id, c]));
const CATEGORY_BY_SLUG = new Map(CATEGORIES.map(c => [c.slug, c]));

const LIST_FIELDS = 'id,date,title,link,categories,jetpack_featured_media_url,_links,_embedded';

const $view = document.getElementById('view');
const $tabs = document.getElementById('tabs');
const $back = document.getElementById('back');
const $searchForm = document.getElementById('search-form');
const $searchInput = document.getElementById('search-input');
const $install = document.getElementById('install');

// 목록 화면을 다시 열 때 스크롤 위치와 불러온 글을 그대로 보여 주기 위한 캐시
const listCache = new Map();
let currentList = null;
let observer = null;

// ---------- 공통 ----------

function decodeEntities(html) {
  const doc = new DOMParser().parseFromString(html || '', 'text/html');
  return doc.documentElement.textContent;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value);
  }
  node.append(...children.filter(Boolean));
  return node;
}

function timeAgo(dateStr) {
  const date = new Date(dateStr);
  const diff = (Date.now() - date.getTime()) / 1000;
  if (diff < 60) return '방금 전';
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}일 전`;
  return date.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' });
}

function featuredImage(post) {
  if (post.jetpack_featured_media_url) return post.jetpack_featured_media_url;
  const media = post._embedded && post._embedded['wp:featuredmedia'] && post._embedded['wp:featuredmedia'][0];
  if (!media || !media.source_url) return null;
  const sizes = media.media_details && media.media_details.sizes;
  const pick = sizes && (sizes.medium_large || sizes.large || sizes.medium);
  return pick ? pick.source_url : media.source_url;
}

function categoryOf(post) {
  for (const id of post.categories || []) {
    if (CATEGORY_BY_ID.has(id)) return CATEGORY_BY_ID.get(id);
  }
  return null;
}

async function api(path, params = {}) {
  const url = new URL(API + path);
  for (const [key, value] of Object.entries(params)) {
    if (value != null && value !== '') url.searchParams.set(key, value);
  }
  const res = await fetch(url);
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return { data: await res.json(), totalPages: Number(res.headers.get('X-WP-TotalPages')) || null };
}

function toast(message) {
  const node = el('div', { class: 'toast', role: 'status', text: message });
  document.body.append(node);
  setTimeout(() => node.remove(), 2000);
}

// 기사 본문에서 스크립트·이벤트 속성을 걷어 낸다
function sanitize(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  doc.querySelectorAll('script, style, noscript, form, ins.adsbygoogle').forEach(n => n.remove());
  doc.querySelectorAll('*').forEach(node => {
    for (const attr of [...node.attributes]) {
      const name = attr.name.toLowerCase();
      const value = attr.value.trim().toLowerCase();
      if (name.startsWith('on')) node.removeAttribute(attr.name);
      else if ((name === 'href' || name === 'src') && value.startsWith('javascript:')) node.removeAttribute(attr.name);
    }
  });
  doc.querySelectorAll('img').forEach(img => {
    img.loading = 'lazy';
    img.decoding = 'async';
    img.removeAttribute('width');
    img.removeAttribute('height');
  });
  doc.querySelectorAll('iframe').forEach(f => { f.loading = 'lazy'; });
  // 사이트 안 다른 글 링크는 앱 안에서 연다
  doc.querySelectorAll('a[href]').forEach(a => {
    const slug = internalSlug(a.getAttribute('href'));
    if (slug) a.setAttribute('href', `#/post/${encodeURIComponent(slug)}`);
    else { a.target = '_blank'; a.rel = 'noopener'; }
  });
  return doc.body.innerHTML;
}

function internalSlug(href) {
  try {
    const url = new URL(href, 'https://kekewo.net/');
    if (url.hostname !== 'kekewo.net' && url.hostname !== 'www.kekewo.net') return null;
    const parts = url.pathname.split('/').filter(Boolean);
    if (parts.length !== 1 || parts[0].includes('.')) return null;
    return decodeURIComponent(parts[0]);
  } catch {
    return null;
  }
}

// ---------- 탭 ----------

function renderTabs(activeSlug) {
  $tabs.replaceChildren(
    el('a', { href: '#/', class: 'tab' + (activeSlug === '' ? ' active' : ''), text: '최신' }),
    ...CATEGORIES.map(c => el('a', {
      href: `#/c/${c.slug}`,
      class: 'tab' + (activeSlug === c.slug ? ' active' : ''),
      text: c.name,
    })),
  );
  const active = $tabs.querySelector('.active');
  if (active) active.scrollIntoView({ block: 'nearest', inline: 'center' });
}

// ---------- 목록 ----------

function cardFor(post, hero) {
  const img = featuredImage(post);
  const cat = categoryOf(post);
  return el('a', { href: `#/post/${post.id}`, class: 'card' + (hero ? ' hero' : '') },
    hero && img ? el('img', { class: 'thumb', src: img, alt: '', loading: 'lazy' }) : null,
    el('div', { class: 'card-body' },
      el('h2', { class: 'card-title', text: decodeEntities(post.title.rendered) }),
      el('div', { class: 'card-meta' },
        cat ? el('span', { class: 'cat', text: cat.name }) : null,
        el('time', { datetime: post.date, text: timeAgo(post.date) }),
      ),
    ),
    !hero && img ? el('img', { class: 'thumb', src: img, alt: '', loading: 'lazy' }) : null,
  );
}

function skeleton(count) {
  return [...Array(count)].map(() => el('div', { class: 'card skeleton' },
    el('div', { class: 'card-body' },
      el('div', { class: 'line' }), el('div', { class: 'line', style: 'width:70%' }),
      el('div', { class: 'line', style: 'width:30%' })),
    el('div', { class: 'thumb' })));
}

function showList(key, title, query) {
  if (observer) observer.disconnect();
  let state = listCache.get(key);
  if (!state) {
    state = { key, query, posts: [], page: 0, done: false, loading: false, scrollY: 0 };
    listCache.set(key, state);
  }
  currentList = state;

  const $items = el('div', { class: 'items' });
  const $sentinel = el('div', { class: 'status' });
  $view.replaceChildren(
    title ? el('div', { class: 'list-title', text: title }) : '',
    $items, $sentinel,
  );
  state.posts.forEach((p, i) => $items.append(cardFor(p, i === 0 && !query.search)));
  window.scrollTo(0, state.scrollY);

  const loadMore = async () => {
    if (state.loading || state.done || currentList !== state) return;
    state.loading = true;
    const placeholders = state.posts.length === 0 ? skeleton(5) : [];
    $items.append(...placeholders);
    $sentinel.replaceChildren(state.posts.length ? '불러오는 중…' : '');
    try {
      const { data, totalPages } = await api('/posts', {
        ...query, per_page: PER_PAGE, page: state.page + 1, _embed: 'wp:featuredmedia', _fields: LIST_FIELDS,
      });
      if (currentList !== state) return;
      placeholders.forEach(n => n.remove());
      state.page += 1;
      data.forEach(p => {
        $items.append(cardFor(p, state.posts.length === 0 && !query.search));
        state.posts.push(p);
      });
      if (data.length < PER_PAGE || (totalPages && state.page >= totalPages)) state.done = true;
      $sentinel.replaceChildren(state.done
        ? (state.posts.length ? '마지막 글입니다' : '글이 없습니다')
        : '');
    } catch (err) {
      placeholders.forEach(n => n.remove());
      // 마지막 페이지를 넘기면 WordPress는 400을 준다
      if (err.status === 400) {
        state.done = true;
        $sentinel.replaceChildren(state.posts.length ? '마지막 글입니다' : '글이 없습니다');
      } else {
        const retry = el('button', { class: 'retry', text: '다시 시도' });
        retry.onclick = loadMore;
        $sentinel.replaceChildren(
          navigator.onLine ? '글을 불러오지 못했어요' : '인터넷에 연결되어 있지 않아요',
          el('br'), retry);
      }
    } finally {
      state.loading = false;
    }
    // 화면이 아직 덜 찼으면 이어서 불러온다
    if (!state.done && currentList === state && $sentinel.getBoundingClientRect().top < innerHeight + 600) {
      loadMore();
    }
  };

  observer = new IntersectionObserver(entries => {
    if (entries.some(e => e.isIntersecting)) loadMore();
  }, { rootMargin: '600px 0px' });
  observer.observe($sentinel);
  if (state.posts.length === 0) loadMore();
}

// ---------- 기사 ----------

async function showPost(idOrSlug) {
  if (observer) observer.disconnect();
  currentList = null;
  window.scrollTo(0, 0);
  $view.replaceChildren(el('div', { class: 'status', text: '불러오는 중…' }));

  let post = null;
  for (const state of listCache.values()) {
    post = state.posts.find(p => String(p.id) === idOrSlug) || null;
    if (post) break;
  }
  try {
    const params = { _embed: 'wp:featuredmedia' };
    if (/^\d+$/.test(idOrSlug)) {
      post = (await api(`/posts/${idOrSlug}`, params)).data;
    } else {
      const found = (await api('/posts', { ...params, slug: idOrSlug })).data;
      post = found[0];
      if (!post) throw Object.assign(new Error('not found'), { status: 404 });
    }
  } catch (err) {
    const retry = el('button', { class: 'retry', text: '다시 시도' });
    retry.onclick = () => showPost(idOrSlug);
    $view.replaceChildren(el('div', { class: 'status' },
      err.status === 404 ? '글을 찾을 수 없어요' : '글을 불러오지 못했어요', el('br'), retry));
    return;
  }

  const title = decodeEntities(post.title.rendered);
  document.title = `${title} - 케케우`;
  const cat = categoryOf(post);
  const content = el('div', { class: 'article-content' });
  content.innerHTML = sanitize(post.content.rendered);

  const share = el('button', { type: 'button', text: '공유하기' });
  share.onclick = async () => {
    if (navigator.share) {
      try { await navigator.share({ title, url: post.link }); } catch { /* 사용자가 취소 */ }
    } else {
      try { await navigator.clipboard.writeText(post.link); toast('링크를 복사했어요'); } catch { toast(post.link); }
    }
  };

  $view.replaceChildren(el('article', { class: 'article' },
    cat ? el('a', { class: 'article-cat', href: `#/c/${cat.slug}`, text: cat.name }) : null,
    el('h1', { text: title }),
    el('div', { class: 'article-meta' },
      el('time', { datetime: post.date, text: new Date(post.date).toLocaleString('ko-KR', {
        year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
      }) })),
    content,
    el('div', { class: 'actions' },
      share,
      el('a', { class: 'primary', href: post.link, target: '_blank', rel: 'noopener', text: '사이트에서 보기' })),
  ));
}

// ---------- 라우터 ----------

function route() {
  if (currentList) currentList.scrollY = window.scrollY;
  const hash = decodeURIComponent(location.hash.slice(1)) || '/';
  const [, kind, arg] = hash.match(/^\/(c|post|search)\/(.+)$/) || [];
  $back.hidden = !kind;

  if (kind === 'post') {
    renderTabs(null);
    showPost(arg);
    return;
  }
  if (kind === 'search') {
    document.title = `"${arg}" 검색 - 케케우`;
    $searchInput.value = arg;
    renderTabs(null);
    showList(`search:${arg}`, `"${arg}" 검색 결과`, { search: arg });
    return;
  }
  $searchInput.value = '';
  const cat = kind === 'c' ? CATEGORY_BY_SLUG.get(arg) : null;
  if (cat) {
    document.title = `${cat.name} - 케케우`;
    renderTabs(cat.slug);
    showList(`c:${cat.slug}`, null, { categories: cat.id });
  } else {
    document.title = '케케우';
    renderTabs('');
    showList('latest', null, {});
  }
}

window.addEventListener('hashchange', route);
$back.addEventListener('click', () => {
  if (history.length > 1) history.back();
  else location.hash = '#/';
});
$searchForm.addEventListener('submit', e => {
  e.preventDefault();
  const q = $searchInput.value.trim();
  if (!q) return;
  $searchInput.blur();
  listCache.delete(`search:${q}`);
  location.hash = `#/search/${encodeURIComponent(q)}`;
});

// ---------- 설치 / 오프라인 ----------

let installPrompt = null;
window.addEventListener('beforeinstallprompt', e => {
  e.preventDefault();
  installPrompt = e;
  $install.hidden = false;
});
$install.addEventListener('click', async () => {
  if (!installPrompt) return;
  installPrompt.prompt();
  await installPrompt.userChoice;
  installPrompt = null;
  $install.hidden = true;
});
window.addEventListener('appinstalled', () => { $install.hidden = true; });

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
}

route();
