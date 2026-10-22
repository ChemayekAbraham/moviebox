import { MOVIES_DATA, CATEGORIES } from './moviesData.js';

// State Management
let currentCategory = 'all';
let searchQuery = '';
let currentMovie = MOVIES_DATA[0];
let watchlist = JSON.parse(localStorage.getItem('moviebox_watchlist') || '[]');

// DOM Elements
const navbar = document.getElementById('navbar');
const categoryFilterBar = document.getElementById('categoryFilterBar');
const mainContent = document.getElementById('mainContent');
const searchInput = document.getElementById('searchInput');
const playerModal = document.getElementById('playerModal');
const modalCloseBtn = document.getElementById('modalCloseBtn');
const streamVideo = document.getElementById('streamVideo');
const watchlistDrawer = document.getElementById('watchlistDrawer');
const watchlistBackdrop = document.getElementById('watchlistBackdrop');
const btnWatchlist = document.getElementById('btnWatchlist');
const watchlistCloseBtn = document.getElementById('watchlistCloseBtn');
const watchlistContainer = document.getElementById('watchlistContainer');
const watchlistCount = document.getElementById('watchlistCount');
const toastContainer = document.getElementById('toastContainer');
const btnMirrorSwitch = document.getElementById('btnMirrorSwitch');
const btnDownloadMovie = document.getElementById('btnDownloadMovie');

// Hero Elements
const heroBanner = document.getElementById('heroBanner');
const heroBadge = document.getElementById('heroBadge');
const heroRating = document.getElementById('heroRating');
const heroYear = document.getElementById('heroYear');
const heroDuration = document.getElementById('heroDuration');
const heroQuality = document.getElementById('heroQuality');
const heroTitle = document.getElementById('heroTitle');
const heroTagline = document.getElementById('heroTagline');
const heroSynopsis = document.getElementById('heroSynopsis');
const heroPlayBtn = document.getElementById('heroPlayBtn');
const heroListBtn = document.getElementById('heroListBtn');
const heroDetailsBtn = document.getElementById('heroDetailsBtn');

// Initialize App
function init() {
  setupNavbarScroll();
  renderCategoryChips();
  renderHero(MOVIES_DATA[0]);
  renderContent();
  updateWatchlistUI();
  setupEventListeners();
}

// Navbar Scroll Effect
function setupNavbarScroll() {
  window.addEventListener('scroll', () => {
    if (window.scrollY > 30) {
      navbar.classList.add('scrolled');
    } else {
      navbar.classList.remove('scrolled');
    }
  });
}

// Category Filter Chips
function renderCategoryChips() {
  categoryFilterBar.innerHTML = CATEGORIES.map(cat => `
    <button class="filter-chip ${cat.id === currentCategory ? 'active' : ''}" data-cat="${cat.id}">
      ${cat.name}
    </button>
  `).join('');

  categoryFilterBar.querySelectorAll('.filter-chip').forEach(btn => {
    btn.addEventListener('click', () => {
      currentCategory = btn.dataset.cat;
      searchQuery = '';
      searchInput.value = '';
      renderCategoryChips();
      updateNavLinks();
      renderContent();
    });
  });
}

function updateNavLinks() {
  document.querySelectorAll('.nav-link').forEach(link => {
    if (link.dataset.category === currentCategory) {
      link.classList.add('active');
    } else {
      link.classList.remove('active');
    }
  });
}

// Render Hero Section
function renderHero(movie) {
  currentMovie = movie;
  heroBanner.style.backgroundImage = `url('${movie.banner}')`;
  heroTitle.textContent = movie.title;
  heroTagline.textContent = movie.tagline || '';
  heroSynopsis.textContent = movie.synopsis;
  heroRating.textContent = movie.rating;
  heroYear.textContent = movie.year;
  heroDuration.textContent = movie.duration;
  heroQuality.textContent = movie.quality;
  heroBadge.textContent = movie.type === 'Anime' ? 'Featured Anime' : movie.category === 'fightzone' ? 'FightZone Spotlight' : 'Featured Blockbuster';

  updateHeroListButton();
}

function updateHeroListButton() {
  const isInList = watchlist.some(m => m.id === currentMovie.id);
  heroListBtn.innerHTML = isInList 
    ? `<svg width="18" height="18" viewBox="0 0 24 24" fill="#e50914" stroke="#e50914" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg> In My List`
    : `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg> My List`;
}

// Render Content
function renderContent() {
  if (searchQuery.trim() !== '') {
    renderSearchResults();
    return;
  }

  if (currentCategory === 'all') {
    renderHomeFeeds();
  } else {
    renderCategorySection(currentCategory);
  }
}

// Render Home Feeds with Multiple Categories & Rankings
function renderHomeFeeds() {
  const trending = MOVIES_DATA.filter(m => m.trending || m.featured);
  const movies = MOVIES_DATA.filter(m => m.category === 'movies');
  const tvSeries = MOVIES_DATA.filter(m => m.category === 'tv');
  const fightZone = MOVIES_DATA.filter(m => m.category === 'fightzone');
  const anime = MOVIES_DATA.filter(m => m.category === 'anime');
  const nollywood = MOVIES_DATA.filter(m => m.category === 'nollywood');

  mainContent.innerHTML = `
    <!-- Trending Now (Ranked 1 to 10) -->
    <section>
      <div class="section-header">
        <h2 class="section-title">
          <span>🔥 Trending Now</span>
          <span class="section-title-badge">TOP 10</span>
        </h2>
      </div>
      <div class="movie-grid">
        ${trending.map((m, idx) => createMovieCard(m, idx + 1)).join('')}
      </div>
    </section>

    <!-- Blockbuster Movies -->
    <section>
      <div class="section-header">
        <h2 class="section-title">🍿 Blockbuster Movies</h2>
      </div>
      <div class="movie-grid">
        ${movies.map(m => createMovieCard(m)).join('')}
      </div>
    </section>

    <!-- TV Shows -->
    <section>
      <div class="section-header">
        <h2 class="section-title">📺 Binge-Worthy TV Series</h2>
      </div>
      <div class="movie-grid">
        ${tvSeries.map(m => createMovieCard(m)).join('')}
      </div>
    </section>

    <!-- FightZone & Combat -->
    <section>
      <div class="section-header">
        <h2 class="section-title">🥊 FightZone & Combat</h2>
      </div>
      <div class="movie-grid">
        ${fightZone.map(m => createMovieCard(m)).join('')}
      </div>
    </section>

    <!-- Anime Universe -->
    <section>
      <div class="section-header">
        <h2 class="section-title">✨ Anime Universe</h2>
      </div>
      <div class="movie-grid">
        ${anime.map(m => createMovieCard(m)).join('')}
      </div>
    </section>

    <!-- Nollywood African Cinema -->
    <section>
      <div class="section-header">
        <h2 class="section-title">🌍 Nollywood & African Cinema</h2>
      </div>
      <div class="movie-grid">
        ${nollywood.map(m => createMovieCard(m)).join('')}
      </div>
    </section>
  `;

  attachCardEvents();
}

function renderCategorySection(catId) {
  let filtered = [];
  let title = '';

  if (catId === 'trending') {
    filtered = MOVIES_DATA.filter(m => m.trending || m.featured);
    title = '🔥 Trending Releases';
  } else {
    filtered = MOVIES_DATA.filter(m => m.category === catId);
    const catObj = CATEGORIES.find(c => c.id === catId);
    title = catObj ? catObj.name : 'Movies';
  }

  mainContent.innerHTML = `
    <section>
      <div class="section-header">
        <h2 class="section-title">${title} (${filtered.length})</h2>
      </div>
      <div class="movie-grid">
        ${filtered.map(m => createMovieCard(m)).join('')}
      </div>
    </section>
  `;

  attachCardEvents();
}

function renderSearchResults() {
  const query = searchQuery.toLowerCase();
  const results = MOVIES_DATA.filter(m => 
    m.title.toLowerCase().includes(query) ||
    m.genres.some(g => g.toLowerCase().includes(query)) ||
    m.cast.some(c => c.toLowerCase().includes(query)) ||
    m.director.toLowerCase().includes(query)
  );

  mainContent.innerHTML = `
    <section>
      <div class="section-header">
        <h2 class="section-title">Search Results for "${escapeHtml(searchQuery)}" (${results.length} found)</h2>
      </div>
      ${results.length === 0 ? `
        <div style="text-align:center; padding:80px 20px; color:var(--text-secondary);">
          <p style="font-size:18px; margin-bottom:10px;">No movies or shows matched your query.</p>
          <p style="font-size:14px; color:var(--text-muted);">Try searching for title keywords, genres like "Sci-Fi", or actors.</p>
        </div>
      ` : `
        <div class="movie-grid">
          ${results.map(m => createMovieCard(m)).join('')}
        </div>
      `}
    </section>
  `;

  attachCardEvents();
}

// Generate HTML for Movie Card
function createMovieCard(movie, rank = null) {
  return `
    <div class="movie-card" data-id="${movie.id}">
      <div class="poster-wrap">
        <img class="poster-img" src="${movie.poster}" alt="${escapeHtml(movie.title)}" loading="lazy" />
        <div class="card-top-badges">
          <span class="quality-badge">${movie.quality}</span>
          <span class="rating-badge">⭐ ${movie.rating}</span>
        </div>
        ${rank ? `<div class="rank-badge">${rank}</div>` : ''}
        <div class="card-overlay">
          <button class="card-play-btn" title="Stream Now">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>
          </button>
        </div>
      </div>
      <div class="movie-details">
        <h3 class="movie-title">${escapeHtml(movie.title)}</h3>
        <div class="movie-meta">
          <span>${movie.year} • ${movie.type}</span>
          <span>${movie.genres[0]}</span>
        </div>
      </div>
    </div>
  `;
}

// Attach Event Listeners to Movie Cards
function attachCardEvents() {
  document.querySelectorAll('.movie-card').forEach(card => {
    card.addEventListener('click', () => {
      const movieId = card.dataset.id;
      const movie = MOVIES_DATA.find(m => m.id === movieId);
      if (movie) {
        openPlayerModal(movie);
      }
    });
  });
}

// Player & Details Modal
function openPlayerModal(movie) {
  currentMovie = movie;

  document.getElementById('modalMovieTitle').textContent = movie.title;
  document.getElementById('modalMovieYear').textContent = movie.year;
  document.getElementById('modalMovieDuration').textContent = movie.duration;
  document.getElementById('modalMovieRating').textContent = `⭐ ${movie.rating} / 10`;
  document.getElementById('modalMovieSynopsis').textContent = movie.synopsis;
  document.getElementById('modalMovieDirector').textContent = movie.director;
  document.getElementById('modalMovieCast').textContent = movie.cast.join(', ');

  const genresContainer = document.getElementById('modalMovieGenres');
  genresContainer.innerHTML = movie.genres.map(g => `<span class="genre-tag">${g}</span>`).join('');

  // Load and play video
  streamVideo.src = movie.videoUrl;
  streamVideo.poster = movie.banner;
  streamVideo.load();
  streamVideo.play().catch(() => {
    // Autoplay policy prevented playback, user can click play
  });

  playerModal.classList.add('open');
  document.body.style.overflow = 'hidden';
}

function closePlayerModal() {
  streamVideo.pause();
  streamVideo.src = '';
  playerModal.classList.remove('open');
  document.body.style.overflow = '';
}

// Watchlist Logic
function toggleWatchlist(movie) {
  const index = watchlist.findIndex(m => m.id === movie.id);
  if (index > -1) {
    watchlist.splice(index, 1);
    showToast(`Removed "${movie.title}" from My List`);
  } else {
    watchlist.push(movie);
    showToast(`Added "${movie.title}" to My List`);
  }

  localStorage.setItem('moviebox_watchlist', JSON.stringify(watchlist));
  updateWatchlistUI();
  updateHeroListButton();
}

function updateWatchlistUI() {
  watchlistCount.textContent = watchlist.length;

  if (watchlist.length === 0) {
    watchlistContainer.innerHTML = `
      <div style="text-align:center; padding:60px 0; color:var(--text-muted);">
        <p>Your watchlist is empty.</p>
        <p style="font-size:12px; margin-top:6px;">Browse movies and click "My List" to save them here.</p>
      </div>
    `;
    return;
  }

  watchlistContainer.innerHTML = watchlist.map(m => `
    <div class="drawer-item" data-id="${m.id}">
      <img src="${m.poster}" alt="${escapeHtml(m.title)}" />
      <div class="drawer-item-info">
        <h4 class="drawer-item-title">${escapeHtml(m.title)}</h4>
        <div style="font-size:12px; color:var(--text-secondary); margin-top:2px;">
          ${m.year} • ⭐ ${m.rating}
        </div>
      </div>
      <button class="btn-remove-list" data-remove="${m.id}" title="Remove from list">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
      </button>
    </div>
  `).join('');

  watchlistContainer.querySelectorAll('.drawer-item').forEach(item => {
    item.addEventListener('click', (e) => {
      if (e.target.closest('.btn-remove-list')) {
        const idToRemove = e.target.closest('.btn-remove-list').dataset.remove;
        const movie = MOVIES_DATA.find(m => m.id === idToRemove);
        if (movie) toggleWatchlist(movie);
        return;
      }
      const movie = MOVIES_DATA.find(m => m.id === item.dataset.id);
      if (movie) {
        closeWatchlistDrawer();
        openPlayerModal(movie);
      }
    });
  });
}

function openWatchlistDrawer() {
  watchlistDrawer.classList.add('open');
  watchlistBackdrop.classList.add('open');
}

function closeWatchlistDrawer() {
  watchlistDrawer.classList.remove('open');
  watchlistBackdrop.classList.remove('open');
}

// Toast Notifications
function showToast(message) {
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.innerHTML = `
    <svg width="18" height="18" viewBox="0 0 24 24" fill="#e50914"><circle cx="12" cy="12" r="10"></circle><polygon points="10 8 16 12 10 16 10 8" fill="#fff"></polygon></svg>
    <span>${escapeHtml(message)}</span>
  `;
  toastContainer.appendChild(toast);

  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateX(50px)';
    setTimeout(() => toast.remove(), 300);
  }, 3500);
}

// Helper: Escape HTML
function escapeHtml(str) {
  return str.replace(/[&<>"']/g, match => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[match]));
}

// Event Listeners Setup
function setupEventListeners() {
  // Navigation Links Click
  document.querySelectorAll('.nav-link').forEach(link => {
    link.addEventListener('click', (e) => {
      e.preventDefault();
      currentCategory = link.dataset.category;
      searchQuery = '';
      searchInput.value = '';
      updateNavLinks();
      renderCategoryChips();
      renderContent();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  });

  // Search Input
  searchInput.addEventListener('input', (e) => {
    searchQuery = e.target.value;
    renderContent();
  });

  // Keyboard shortcut '/' to search
  window.addEventListener('keydown', (e) => {
    if (e.key === '/' && document.activeElement !== searchInput) {
      e.preventDefault();
      searchInput.focus();
    } else if (e.key === 'Escape') {
      closePlayerModal();
      closeWatchlistDrawer();
    }
  });

  // Hero Buttons
  heroPlayBtn.addEventListener('click', () => openPlayerModal(currentMovie));
  heroListBtn.addEventListener('click', () => toggleWatchlist(currentMovie));
  heroDetailsBtn.addEventListener('click', () => openPlayerModal(currentMovie));

  // Modal Close
  modalCloseBtn.addEventListener('click', closePlayerModal);
  playerModal.addEventListener('click', (e) => {
    if (e.target === playerModal) closePlayerModal();
  });

  // Server Switcher inside modal
  document.querySelectorAll('.server-btn[data-server]').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.server-btn[data-server]').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      const serverNum = btn.dataset.server;
      const currentTime = streamVideo.currentTime;
      streamVideo.load();
      streamVideo.currentTime = currentTime;
      streamVideo.play();
      showToast(`Switched to Streaming Server ${serverNum} (Fast CDN)`);
    });
  });

  // Download Button
  btnDownloadMovie.addEventListener('click', () => {
    showToast(`Starting high-speed HD download of "${currentMovie.title}"...`);
    const a = document.createElement('a');
    a.href = currentMovie.videoUrl;
    a.download = `${currentMovie.title}.mp4`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  });

  // Watchlist Drawer
  btnWatchlist.addEventListener('click', openWatchlistDrawer);
  watchlistCloseBtn.addEventListener('click', closeWatchlistDrawer);
  watchlistBackdrop.addEventListener('click', closeWatchlistDrawer);

  // Brand Logo Click
  document.getElementById('brandLogo').addEventListener('click', () => {
    currentCategory = 'all';
    searchQuery = '';
    searchInput.value = '';
    updateNavLinks();
    renderCategoryChips();
    renderContent();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  // Mirror Switcher in Navbar
  const mirrors = ['https://h5.aoneroom.com', 'https://themoviebox.org', 'https://movieboxonline.net'];
  let mirrorIndex = 0;
  btnMirrorSwitch.addEventListener('click', () => {
    mirrorIndex = (mirrorIndex + 1) % mirrors.length;
    showToast(`Connected to Streaming Mirror: ${mirrors[mirrorIndex]}`);
  });
}

// Start
init();
