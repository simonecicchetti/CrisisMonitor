/**
 * Crisis Monitor - Main Application
 * Early Warning Platform v2.2
 */

// ============================================
// GLOBAL UTILITIES
// ============================================
const Utils = {
  /**
   * Escape HTML to prevent XSS
   */
  escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  },

  /**
   * Sanitize URL - only allow http/https
   * @param {string} url - URL to sanitize
   * @param {string} fallback - Fallback value if invalid (default '#')
   * @returns {string|null} Sanitized URL or fallback
   */
  sanitizeUrl(url, fallback = '#') {
    if (!url) return fallback;
    try {
      const parsed = new URL(url);
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
        return parsed.href;
      }
    } catch {
      // Invalid URL
    }
    return fallback;
  },

  /**
   * Format relative time
   */
  formatTimeAgo(date) {
    if (!date) return '';
    const d = date instanceof Date ? date : new Date(date);
    const now = new Date();
    const diffMs = now - d;
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    if (diffDays < 7) return `${diffDays}d ago`;
    return d.toLocaleDateString();
  },

  /**
   * Debounce function
   */
  debounce(fn, delay) {
    let timer;
    return (...args) => {
      clearTimeout(timer);
      timer = setTimeout(() => fn(...args), delay);
    };
  },

  /**
   * Calculate exponential backoff delay
   * @param {number} attempt - Current attempt number (0-based)
   * @param {number} baseDelay - Base delay in ms (default 1000)
   * @param {number} maxDelay - Maximum delay in ms (default 60000)
   * @returns {number} Delay in ms with jitter
   */
  getBackoffDelay(attempt, baseDelay = 1000, maxDelay = 60000) {
    const delay = Math.min(baseDelay * Math.pow(2, attempt), maxDelay);
    // Add 10% jitter to prevent thundering herd
    const jitter = delay * 0.1 * Math.random();
    return Math.floor(delay + jitter);
  },

  /**
   * Retry a function with exponential backoff
   * @param {Function} fn - Async function to retry
   * @param {number} maxRetries - Maximum retry attempts (default 5)
   * @param {number} baseDelay - Base delay in ms (default 1000)
   * @returns {Promise} Result of the function
   */
  async retryWithBackoff(fn, maxRetries = 5, baseDelay = 1000) {
    let lastError;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await fn();
      } catch (error) {
        lastError = error;
        if (attempt < maxRetries) {
          const delay = this.getBackoffDelay(attempt, baseDelay);
          console.log(`[Retry] Attempt ${attempt + 1}/${maxRetries} failed, retrying in ${delay}ms`);
          await new Promise(resolve => setTimeout(resolve, delay));
        }
      }
    }
    throw lastError;
  },

  /**
   * Generate skeleton loading HTML
   * @param {string} type - Type of skeleton: 'card', 'text', 'list', 'table'
   * @param {number} count - Number of skeleton items (default 3)
   * @returns {string} HTML string of skeleton elements
   */
  skeleton(type, count = 3) {
    const templates = {
      card: `
        <div class="skeleton-card skeleton" aria-hidden="true">
          <div class="skeleton-header">
            <div class="skeleton-avatar skeleton"></div>
            <div class="skeleton-title skeleton skeleton-text" style="width: 60%"></div>
          </div>
          <div class="skeleton-body">
            <div class="skeleton skeleton-text" style="width: 100%"></div>
            <div class="skeleton skeleton-text" style="width: 80%"></div>
            <div class="skeleton skeleton-text" style="width: 40%"></div>
          </div>
        </div>
      `,
      text: `<div class="skeleton skeleton-text" aria-hidden="true" style="width: ${70 + Math.random() * 30}%"></div>`,
      list: `
        <div class="skeleton-list-item" aria-hidden="true">
          <div class="skeleton skeleton-icon"></div>
          <div class="skeleton-content">
            <div class="skeleton skeleton-text" style="width: 70%"></div>
            <div class="skeleton skeleton-text" style="width: 40%"></div>
          </div>
        </div>
      `,
      table: `
        <tr class="skeleton-row" aria-hidden="true">
          <td><div class="skeleton skeleton-text" style="width: 80%"></div></td>
          <td><div class="skeleton skeleton-text" style="width: 60%"></div></td>
          <td><div class="skeleton skeleton-text" style="width: 50%"></div></td>
        </tr>
      `,
      news: `
        <div class="news-skeleton" aria-hidden="true">
          <div class="skeleton skeleton-text" style="width: 90%"></div>
          <div class="skeleton skeleton-text" style="width: 70%"></div>
          <div class="skeleton skeleton-meta">
            <div class="skeleton skeleton-text" style="width: 100px"></div>
            <div class="skeleton skeleton-text" style="width: 60px"></div>
          </div>
        </div>
      `,
      alert: `
        <div class="alert-skeleton" aria-hidden="true">
          <div class="skeleton skeleton-badge"></div>
          <div class="skeleton-content">
            <div class="skeleton skeleton-text" style="width: 80%"></div>
            <div class="skeleton skeleton-text" style="width: 60%"></div>
          </div>
        </div>
      `
    };

    const template = templates[type] || templates.card;
    return Array(count).fill(template).join('');
  },

  /**
   * Show skeleton loading in a container
   * @param {HTMLElement|string} container - Container element or selector
   * @param {string} type - Type of skeleton
   * @param {number} count - Number of skeleton items
   */
  showSkeleton(container, type = 'card', count = 3) {
    const el = typeof container === 'string' ? document.querySelector(container) : container;
    if (!el) return;
    el.innerHTML = `<div class="skeleton-container" role="status" aria-label="Loading content">${this.skeleton(type, count)}</div>`;
  },

  /**
   * Clear skeleton loading from container
   * @param {HTMLElement|string} container - Container element or selector
   */
  clearSkeleton(container) {
    const el = typeof container === 'string' ? document.querySelector(container) : container;
    if (!el) return;
    const skeleton = el.querySelector('.skeleton-container');
    if (skeleton) skeleton.remove();
  },

  /**
   * Animate a number counter from 0 to target
   * @param {HTMLElement|string} element - Element to animate
   * @param {number} target - Target number
   * @param {number} duration - Animation duration in ms (default 1000)
   * @param {string} suffix - Optional suffix (e.g., '%', 'K')
   */
  animateCounter(element, target, duration = 1000, suffix = '') {
    const el = typeof element === 'string' ? document.querySelector(element) : element;
    if (!el) return;

    const start = 0;
    const startTime = performance.now();
    const isFloat = target % 1 !== 0;

    const animate = (currentTime) => {
      const elapsed = currentTime - startTime;
      const progress = Math.min(elapsed / duration, 1);

      // Ease out quad
      const easeOut = 1 - Math.pow(1 - progress, 3);
      const current = start + (target - start) * easeOut;

      el.textContent = (isFloat ? current.toFixed(1) : Math.floor(current)) + suffix;
      el.classList.add('counting');

      if (progress < 1) {
        requestAnimationFrame(animate);
      } else {
        el.textContent = target + suffix;
        el.classList.remove('counting');
      }
    };

    requestAnimationFrame(animate);
  },

  /**
   * Initialize all counters on page
   * @param {string} selector - CSS selector for counter elements
   */
  initCounters(selector = '[data-count]') {
    document.querySelectorAll(selector).forEach(el => {
      const target = parseFloat(el.dataset.count);
      const suffix = el.dataset.suffix || '';
      const duration = parseInt(el.dataset.duration) || 1000;
      if (!isNaN(target)) {
        this.animateCounter(el, target, duration, suffix);
      }
    });
  }
};

// ============================================
// TOAST NOTIFICATION SYSTEM
// ============================================
const Toast = {
  container: null,

  init() {
    if (this.container) return;
    this.container = document.createElement('div');
    this.container.id = 'toast-container';
    this.container.style.cssText = `
      position: fixed; bottom: 20px; right: 20px; z-index: 10000;
      display: flex; flex-direction: column; gap: 8px;
    `;
    document.body.appendChild(this.container);
  },

  show(message, type = 'info', duration = 4000) {
    this.init();
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.style.cssText = `
      padding: 12px 16px; border-radius: 8px; color: white;
      font-size: 14px; max-width: 350px; opacity: 0;
      transform: translateX(100%); transition: all 0.3s ease;
      background: ${type === 'error' ? '#ff453a' : type === 'success' ? '#30d158' : '#64d2ff'};
      box-shadow: 0 4px 12px rgba(0,0,0,0.3);
    `;
    toast.textContent = message;
    this.container.appendChild(toast);

    // Animate in
    requestAnimationFrame(() => {
      toast.style.opacity = '1';
      toast.style.transform = 'translateX(0)';
    });

    // Auto remove
    setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateX(100%)';
      setTimeout(() => toast.remove(), 300);
    }, duration);
  },

  error(message) { this.show(message, 'error', 5000); },
  success(message) { this.show(message, 'success'); },
  info(message) { this.show(message, 'info'); }
};

// ============================================
// COMMAND PALETTE (cmd+K)
// ============================================
const CommandPalette = {
  isOpen: false,
  overlay: null,
  input: null,
  results: null,
  selectedIndex: 0,
  commands: [],

  init() {
    this.createPalette();
    this.registerCommands();
    this.setupKeyboardShortcuts();
  },

  createPalette() {
    this.overlay = document.createElement('div');
    this.overlay.id = 'command-palette-overlay';
    this.overlay.innerHTML = `
      <div class="command-palette" role="dialog" aria-label="Command palette">
        <div class="command-input-wrapper">
          <svg class="command-search-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <path d="m21 21-4.35-4.35"/>
          </svg>
          <input type="text" id="command-input" placeholder="Search commands, countries, or ask AI..." autocomplete="off" />
          <kbd class="command-shortcut">ESC</kbd>
        </div>
        <div class="command-results" id="command-results"></div>
        <div class="command-footer">
          <span><kbd>↑</kbd><kbd>↓</kbd> Navigate</span>
          <span><kbd>↵</kbd> Select</span>
          <span><kbd>ESC</kbd> Close</span>
        </div>
      </div>
    `;
    this.overlay.style.cssText = `
      position: fixed; inset: 0; z-index: 9999;
      background: rgba(0, 0, 0, 0.6);
      backdrop-filter: blur(4px);
      display: none; align-items: flex-start; justify-content: center;
      padding-top: 15vh;
    `;
    document.body.appendChild(this.overlay);

    this.input = this.overlay.querySelector('#command-input');
    this.results = this.overlay.querySelector('#command-results');

    // Event listeners
    this.overlay.addEventListener('click', (e) => {
      if (e.target === this.overlay) this.close();
    });

    this.input.addEventListener('input', () => this.search(this.input.value));
    this.input.addEventListener('keydown', (e) => this.handleKeydown(e));
  },

  registerCommands() {
    this.commands = [
      // Navigation commands
      { id: 'nav-overview', label: 'Go to Overview', category: 'Navigation', icon: '📊', action: () => SidebarManager.switchSection('overview') },
      { id: 'nav-drivers', label: 'Go to Crisis Drivers', category: 'Navigation', icon: '🌊', action: () => SidebarManager.switchSection('drivers') },
      { id: 'nav-countries', label: 'Go to Country Analysis', category: 'Navigation', icon: '🗺️', action: () => SidebarManager.switchSection('countries') },
      { id: 'nav-intelligence', label: 'Go to Intelligence', category: 'Navigation', icon: '📡', action: () => SidebarManager.switchSection('intelligence') },

      // Actions
      { id: 'action-refresh', label: 'Refresh All Data', category: 'Actions', icon: '🔄', action: () => location.reload() },
      { id: 'action-theme', label: 'Toggle Dark/Light Mode', category: 'Actions', icon: '🌙', action: () => document.querySelector('.theme-toggle')?.click() },
      { id: 'action-ai-analysis', label: 'Open AI Crisis Analysis', category: 'AI', icon: '✨', action: () => document.getElementById('ai-analyze-btn')?.click() },
      { id: 'action-ai-brief', label: 'Open AI Brief', category: 'AI', icon: '📋', action: () => document.getElementById('ai-brief-btn')?.click() },

      // Export
      { id: 'export-csv', label: 'Export Risk Scores (CSV)', category: 'Export', icon: '📊', action: () => window.open('/api/export/csv', '_blank') },
      { id: 'export-pdf', label: 'Export Risk Report (PDF)', category: 'Export', icon: '📄', action: () => window.open('/api/export/pdf', '_blank') },

      // Quick filters
      { id: 'filter-critical', label: 'Show Critical Countries Only', category: 'Filters', icon: '🔴', action: () => this.filterCountries('critical') },
      { id: 'filter-high', label: 'Show High Risk Countries', category: 'Filters', icon: '🟠', action: () => this.filterCountries('high') },
      { id: 'filter-food', label: 'Show Food Crisis Countries', category: 'Filters', icon: '🍚', action: () => this.searchCountries('food crisis') },
      { id: 'filter-conflict', label: 'Show Conflict Zones', category: 'Filters', icon: '⚔️', action: () => this.searchCountries('conflict') },

      // Countries (will be populated dynamically)
      { id: 'country-ssd', label: 'South Sudan', category: 'Countries', icon: '🇸🇸', action: () => this.goToCountry('SSD') },
      { id: 'country-sdn', label: 'Sudan', category: 'Countries', icon: '🇸🇩', action: () => this.goToCountry('SDN') },
      { id: 'country-yem', label: 'Yemen', category: 'Countries', icon: '🇾🇪', action: () => this.goToCountry('YEM') },
      { id: 'country-afg', label: 'Afghanistan', category: 'Countries', icon: '🇦🇫', action: () => this.goToCountry('AFG') },
      { id: 'country-eth', label: 'Ethiopia', category: 'Countries', icon: '🇪🇹', action: () => this.goToCountry('ETH') },
      { id: 'country-som', label: 'Somalia', category: 'Countries', icon: '🇸🇴', action: () => this.goToCountry('SOM') },
      { id: 'country-hti', label: 'Haiti', category: 'Countries', icon: '🇭🇹', action: () => this.goToCountry('HTI') },
      { id: 'country-syr', label: 'Syria', category: 'Countries', icon: '🇸🇾', action: () => this.goToCountry('SYR') },
      { id: 'country-cod', label: 'DR Congo', category: 'Countries', icon: '🇨🇩', action: () => this.goToCountry('COD') },
      { id: 'country-mmr', label: 'Myanmar', category: 'Countries', icon: '🇲🇲', action: () => this.goToCountry('MMR') },
    ];
  },

  setupKeyboardShortcuts() {
    document.addEventListener('keydown', (e) => {
      // cmd+K or ctrl+K to open
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        this.toggle();
      }
      // Escape to close
      if (e.key === 'Escape' && this.isOpen) {
        this.close();
      }
    });
  },

  toggle() {
    this.isOpen ? this.close() : this.open();
  },

  open() {
    this.isOpen = true;
    this.overlay.style.display = 'flex';
    this.input.value = '';
    this.selectedIndex = 0;
    this.search('');
    setTimeout(() => this.input.focus(), 50);
    document.body.style.overflow = 'hidden';
  },

  close() {
    this.isOpen = false;
    this.overlay.style.display = 'none';
    document.body.style.overflow = '';
  },

  search(query) {
    const q = query.toLowerCase().trim();
    let filtered = this.commands;

    if (q) {
      filtered = this.commands.filter(cmd =>
        cmd.label.toLowerCase().includes(q) ||
        cmd.category.toLowerCase().includes(q)
      );
    }

    // Group by category
    const grouped = {};
    filtered.forEach(cmd => {
      if (!grouped[cmd.category]) grouped[cmd.category] = [];
      grouped[cmd.category].push(cmd);
    });

    // Render results
    let html = '';
    let index = 0;

    Object.entries(grouped).forEach(([category, cmds]) => {
      html += `<div class="command-category">${category}</div>`;
      cmds.forEach(cmd => {
        html += `
          <div class="command-item ${index === this.selectedIndex ? 'selected' : ''}"
               data-index="${index}" data-id="${cmd.id}">
            <span class="command-icon">${cmd.icon}</span>
            <span class="command-label">${this.highlight(cmd.label, q)}</span>
          </div>
        `;
        index++;
      });
    });

    // AI query suggestion if no exact match
    if (q.length > 3 && filtered.length < 3) {
      html += `
        <div class="command-category">Ask AI</div>
        <div class="command-item command-ai ${index === this.selectedIndex ? 'selected' : ''}"
             data-index="${index}" data-query="${Utils.escapeHtml(q)}">
          <span class="command-icon">✨</span>
          <span class="command-label">Ask Claude: "${Utils.escapeHtml(q)}"</span>
        </div>
      `;
    }

    this.results.innerHTML = html || '<div class="command-empty">No results found</div>';
    this.filteredCommands = filtered;

    // Add click handlers
    this.results.querySelectorAll('.command-item').forEach(item => {
      item.addEventListener('click', () => this.executeItem(item));
      item.addEventListener('mouseenter', () => {
        this.selectedIndex = parseInt(item.dataset.index);
        this.updateSelection();
      });
    });
  },

  highlight(text, query) {
    if (!query) return text;
    const regex = new RegExp(`(${query})`, 'gi');
    return text.replace(regex, '<mark>$1</mark>');
  },

  handleKeydown(e) {
    const items = this.results.querySelectorAll('.command-item');
    const count = items.length;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        this.selectedIndex = (this.selectedIndex + 1) % count;
        this.updateSelection();
        break;
      case 'ArrowUp':
        e.preventDefault();
        this.selectedIndex = (this.selectedIndex - 1 + count) % count;
        this.updateSelection();
        break;
      case 'Enter':
        e.preventDefault();
        const selected = items[this.selectedIndex];
        if (selected) this.executeItem(selected);
        break;
    }
  },

  updateSelection() {
    this.results.querySelectorAll('.command-item').forEach((item, i) => {
      item.classList.toggle('selected', i === this.selectedIndex);
      if (i === this.selectedIndex) {
        item.scrollIntoView({ block: 'nearest' });
      }
    });
  },

  executeItem(item) {
    const cmdId = item.dataset.id;
    const query = item.dataset.query;

    if (query) {
      // AI query
      this.close();
      this.askAI(query);
      return;
    }

    const cmd = this.commands.find(c => c.id === cmdId);
    if (cmd?.action) {
      this.close();
      cmd.action();
    }
  },

  // Helper methods for command actions
  filterCountries(level) {
    SidebarManager.switchSection('countries');
    Toast.info(`Filtering ${level} countries...`);
  },

  searchCountries(term) {
    SidebarManager.switchSection('countries');
    Toast.info(`Searching for "${term}"...`);
  },

  goToCountry(iso3) {
    SidebarManager.switchSection('countries');
    // Trigger country detail if CountryDetailManager exists
    if (typeof CountryDetailManager !== 'undefined') {
      setTimeout(() => CountryDetailManager.open(iso3), 300);
    }
  },

  askAI(query) {
    // Open AI modal with the query
    const modal = document.getElementById('ai-analysis-modal');
    const input = document.getElementById('custom-query-input');
    if (modal && input) {
      modal.style.display = 'flex';
      input.value = query;
      Toast.info('Type your question and press Enter...');
    }
  }
};

// ============================================
// OFFLINE BANNER (Persistent)
// ============================================
const OfflineBanner = {
  banner: null,
  isOffline: false,

  init() {
    this.createBanner();
    this.setupListeners();
    // Check initial state
    if (!navigator.onLine) {
      this.show();
    }
  },

  createBanner() {
    this.banner = document.createElement('div');
    this.banner.id = 'offline-banner';
    this.banner.innerHTML = `
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <line x1="1" y1="1" x2="23" y2="23"/>
        <path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/>
        <path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/>
        <path d="M10.71 5.05A16 16 0 0 1 22.58 9"/>
        <path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/>
        <path d="M8.53 16.11a6 6 0 0 1 6.95 0"/>
        <line x1="12" y1="20" x2="12.01" y2="20"/>
      </svg>
      <span>You're offline - showing cached data</span>
      <button id="offline-dismiss" aria-label="Dismiss">&times;</button>
    `;
    this.banner.style.cssText = `
      position: fixed; top: 0; left: 0; right: 0; z-index: 9999;
      display: none; align-items: center; justify-content: center; gap: 8px;
      padding: 10px 16px; background: #ff9500; color: #000;
      font-size: 14px; font-weight: 500;
      box-shadow: 0 2px 8px rgba(0,0,0,0.2);
    `;
    document.body.prepend(this.banner);

    // Dismiss button
    this.banner.querySelector('#offline-dismiss').addEventListener('click', () => {
      this.banner.style.display = 'none';
    });
  },

  setupListeners() {
    window.addEventListener('online', () => {
      this.isOffline = false;
      this.hide();
      Toast.success('Back online');
    });

    window.addEventListener('offline', () => {
      this.isOffline = true;
      this.show();
      Toast.error('You are offline');
    });
  },

  show() {
    if (this.banner) {
      this.banner.style.display = 'flex';
      // Shift body content down
      document.body.style.paddingTop = '44px';
    }
  },

  hide() {
    if (this.banner) {
      this.banner.style.display = 'none';
      document.body.style.paddingTop = '0';
    }
  }
};

// ============================================
// THEME MANAGEMENT
// ============================================
const ThemeManager = {
  init() {
    // Check system preference
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)');
    const savedTheme = localStorage.getItem('theme');

    if (savedTheme) {
      this.setTheme(savedTheme);
    } else {
      this.setTheme(prefersDark.matches ? 'dark' : 'light');
    }

    // Listen for system changes
    prefersDark.addEventListener('change', (e) => {
      if (!localStorage.getItem('theme')) {
        this.setTheme(e.matches ? 'dark' : 'light');
      }
    });

    // Bind toggle button
    const toggleBtn = document.getElementById('theme-toggle');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', () => this.toggle());
    }
  },

  setTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    this.updateToggleIcon(theme);
  },

  toggle() {
    const current = document.documentElement.getAttribute('data-theme') || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    localStorage.setItem('theme', next);
    this.setTheme(next);
  },

  updateToggleIcon(theme) {
    const btn = document.getElementById('theme-toggle');
    if (btn) {
      btn.innerHTML = theme === 'dark'
        ? '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>'
        : '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>';
      // Dynamic aria-label for accessibility
      btn.setAttribute('aria-label', theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode');
    }
  }
};

// ============================================
// SERVICE WORKER REGISTRATION
// ============================================
const PWAManager = {
  async init() {
    if ('serviceWorker' in navigator) {
      try {
        const registration = await navigator.serviceWorker.register('/sw.js');
        console.log('SW registered:', registration.scope);
      } catch (error) {
        console.log('SW registration failed:', error);
      }
    }
  }
};

// ============================================
// ANIMATION UTILITIES
// ============================================
const Animations = {
  observeElements() {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('animate-in');
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.1, rootMargin: '50px' }
    );

    document.querySelectorAll('.glass-card, .stat-card').forEach((el, i) => {
      el.style.opacity = '0';
      el.classList.add(`stagger-${Math.min(i + 1, 6)}`);
      observer.observe(el);
    });
  },

  countUp(element, target, duration = 1000) {
    const start = 0;
    const startTime = performance.now();

    const update = (currentTime) => {
      const elapsed = currentTime - startTime;
      const progress = Math.min(elapsed / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      const current = Math.round(start + (target - start) * eased);

      element.textContent = current.toLocaleString();

      if (progress < 1) {
        requestAnimationFrame(update);
      }
    };

    requestAnimationFrame(update);
  }
};

// ============================================
// DATA FETCHING
// ============================================
const DataManager = {
  cache: new Map(),
  cacheTimeout: 60000, // 1 minute

  async fetch(endpoint) {
    const cached = this.cache.get(endpoint);
    if (cached && Date.now() - cached.timestamp < this.cacheTimeout) {
      return cached.data;
    }

    try {
      const response = await fetch(endpoint);
      const data = await response.json();
      this.cache.set(endpoint, { data, timestamp: Date.now() });
      return data;
    } catch (error) {
      console.error(`Error fetching ${endpoint}:`, error);
      return cached?.data || null;
    }
  },

  async getStats() {
    return this.fetch('/api/stats');
  },

  async getCountries() {
    return this.fetch('/api/countries');
  },

  async getHazards() {
    return this.fetch('/api/hazards');
  },

  async getMigrationMonthly() {
    return this.fetch('/api/migration/monthly');
  }
};

// ============================================
// CHARTS
// ============================================
const ChartManager = {
  migrationChart: null,

  async initMigrationChart() {
    const ctx = document.getElementById('migration-chart');
    if (!ctx) return;

    const data = await DataManager.getMigrationMonthly();
    if (!data) return;

    const labels = Object.keys(data);
    const values = Object.values(data);
    const maxValue = Math.max(...values);

    // Create bar chart with CSS
    const container = ctx.parentElement;
    container.innerHTML = '';

    const barsHtml = labels.map((label, i) => {
      const height = (values[i] / maxValue) * 100;
      return `
        <div class="trend-bar-item">
          <div class="trend-value">${values[i].toLocaleString()}</div>
          <div class="trend-bar" style="height: ${height}%"></div>
          <div class="trend-label">${label.substring(5)}</div>
        </div>
      `;
    }).join('');

    container.innerHTML = `<div class="trend-bars">${barsHtml}</div>`;
  }
};

// ============================================
// LIVE UPDATE INDICATOR
// ============================================
const LiveIndicator = {
  init() {
    this.updateTimestamp();
    setInterval(() => this.updateTimestamp(), 60000);
  },

  updateTimestamp() {
    const el = document.getElementById('last-updated');
    if (el) {
      const now = new Date();
      const time = now.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
      el.textContent = `Updated ${time}`;
    }
  }
};

// ============================================
// KEYBOARD SHORTCUTS
// ============================================
const KeyboardShortcuts = {
  init() {
    document.addEventListener('keydown', (e) => {
      // Cmd/Ctrl + K: Focus search (future feature)
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        // TODO: Open search
      }

      // T: Toggle theme
      if (e.key === 't' && !e.target.matches('input, textarea')) {
        ThemeManager.toggle();
      }

      // R: Refresh data
      if (e.key === 'r' && !e.target.matches('input, textarea')) {
        window.location.reload();
      }
    });
  }
};

// ============================================
// HAPTIC FEEDBACK (for supported devices)
// ============================================
const Haptics = {
  light() {
    if ('vibrate' in navigator) {
      navigator.vibrate(10);
    }
  },

  medium() {
    if ('vibrate' in navigator) {
      navigator.vibrate(20);
    }
  }
};

// ============================================
// CONFLICT MEDIA MONITORING (GDELT)
// ============================================
const ConflictMonitor = {
  loaded: false,
  retryCount: 0,
  maxRetries: 5,

  async init() {
    if (this.loaded) return;

    const container = document.getElementById('conflict-spikes-container');
    if (!container) return;

    container.innerHTML = `
      <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
        <div class="loading-spinner"></div>
        Loading conflict data from GDELT...
      </div>
    `;

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch('/api/conflict/spikes', { signal: controller.signal });
      clearTimeout(timeoutId);

      const result = await response.json();

      if (result.status === 'LOADING') {
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
            <div class="loading-spinner"></div>
            ${result.message || 'Loading conflict data...'}
          </div>
        `;
        // Use exponential backoff for LOADING state
        const delay = Utils.getBackoffDelay(this.retryCount, 5000, 30000);
        this.retryCount++;
        setTimeout(() => { this.loaded = false; this.init(); }, delay);
        return;
      }

      const spikes = result.data || result;
      DataManager.cache.set('/api/conflict/spikes', { data: spikes, timestamp: Date.now() });
      this.render(container, spikes, result.status === 'STALE');
      this.loaded = true;
      this.retryCount = 0; // Reset on success
    } catch (error) {
      console.error('Error loading conflict spikes:', error);

      if (this.retryCount < this.maxRetries) {
        const delay = Utils.getBackoffDelay(this.retryCount, 5000, 60000);
        this.retryCount++;
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
            Loading conflict data... (retry ${this.retryCount}/${this.maxRetries})
          </div>
        `;
        setTimeout(() => { this.loaded = false; this.init(); }, delay);
      } else {
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
            Unable to load conflict data. <a href="#" onclick="ConflictMonitor.retryCount=0; ConflictMonitor.loaded=false; ConflictMonitor.init(); return false;">Retry</a>
          </div>
        `;
      }
    }
  },

  render(container, spikes, isStale = false) {
    if (!spikes || spikes.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          No elevated media spikes detected
        </div>
      `;
      return;
    }

    const staleIndicator = isStale ? `
      <div style="padding: 8px 16px; background: rgba(255, 159, 10, 0.1); border-radius: 8px; margin-bottom: 12px; font-size: 0.75rem; color: var(--warning);">
        ⟳ Data may be outdated, refreshing in background...
      </div>
    ` : '';

    // Filter to show only elevated spikes (z > 0.5) and sort by z-score
    // Note: API returns 'zscore' (lowercase)
    const elevated = spikes
      .filter(s => s.zscore != null && s.zscore > 0.5)
      .sort((a, b) => b.zscore - a.zscore)
      .slice(0, 15);

    if (elevated.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          All countries at normal coverage levels
        </div>
      `;
      return;
    }

    const rows = elevated.map(spike => {
      const rowClass = this.getRowClass(spike.spikeLevel);
      const badgeClass = this.getBadgeClass(spike.spikeLevel);
      const zDisplay = spike.zscore != null ? spike.zscore.toFixed(1) : '-';

      // Format headlines as context chips
      const headlines = spike.topHeadlines && spike.topHeadlines.length > 0
        ? `<div class="headline-chips">${spike.topHeadlines.map(h =>
            `<span class="headline-chip" title="${h}">${h}</span>`
          ).join('')}</div>`
        : '';

      return `
        <tr class="${rowClass}">
          <td>
            <div class="country-cell">
              <strong>${spike.countryName || spike.iso3}</strong>
              ${headlines}
            </div>
          </td>
          <td class="value-cell">
            <span class="badge ${badgeClass}">${spike.spikeLevel}</span>
          </td>
          <td class="value-cell">${zDisplay}</td>
          <td class="value-cell text-secondary">${spike.articlesLast7Days || 0}</td>
        </tr>
      `;
    }).join('');

    container.innerHTML = `
      ${staleIndicator}
      <table class="data-table">
        <thead>
          <tr>
            <th>Country / Headlines</th>
            <th>Level</th>
            <th>Z</th>
            <th>7d</th>
          </tr>
        </thead>
        <tbody>
          ${rows}
        </tbody>
      </table>
    `;
  },

  getRowClass(level) {
    switch (level) {
      case 'CRITICAL': return 'critical-row';
      case 'HIGH': return 'high-row';
      case 'ELEVATED': return '';
      default: return '';
    }
  },

  getBadgeClass(level) {
    switch (level) {
      case 'CRITICAL': return 'critical';
      case 'HIGH': return 'high';
      case 'ELEVATED': return 'medium';
      case 'MODERATE': return '';
      default: return '';
    }
  }
};

// ============================================
// TAB NAVIGATION
// ============================================
const TabManager = {
  init() {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabPanes = document.querySelectorAll('.tab-pane');

    if (tabBtns.length === 0) return;

    tabBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        const tabId = btn.dataset.tab;

        // Update button states
        tabBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');

        // Update pane states
        tabPanes.forEach(pane => {
          pane.classList.remove('active');
          if (pane.id === `tab-${tabId}`) {
            pane.classList.add('active');
          }
        });

        // Load tab data if not already loaded
        this.loadTabData(tabId);

        Haptics.light();
      });
    });

    // Load first tab data
    this.loadTabData('risk-score');
  },

  loadTabData(tabId) {
    switch (tabId) {
      case 'risk-score':
        RiskScoreMonitor.init();
        break;
      case 'climate':
        ClimateMonitor.init();
        break;
      case 'currency':
        CurrencyMonitor.init();
        break;
      case 'conflict':
        ConflictMonitor.init();
        break;
      case 'food-security':
        IPCMonitor.init();
        break;
    }
  }
};

// ============================================
// RISK SCORE MONITORING
// ============================================
const RiskScoreMonitor = {
  loaded: false,
  retryCount: 0,
  maxRetries: 5,

  async init() {
    if (this.loaded) return;

    const container = document.getElementById('risk-scores-container');
    if (!container) return;

    container.innerHTML = `
      <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
        <div class="loading-spinner"></div>
        Calculating predictive risk scores...
      </div>
    `;

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch('/api/risk/scores', { signal: controller.signal });
      clearTimeout(timeoutId);

      const result = await response.json();

      if (result.status === 'LOADING') {
        const delay = Utils.getBackoffDelay(this.retryCount, 5000, 30000);
        this.retryCount++;
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
            <div class="loading-spinner"></div>
            ${result.message || 'Loading risk scores...'}
          </div>
        `;
        setTimeout(() => { this.loaded = false; this.init(); }, delay);
        return;
      }

      const scores = result.data || result;
      const isStale = result.status === 'STALE';

      this.render(container, scores, isStale);
      this.loaded = true;
      this.retryCount = 0;
    } catch (error) {
      console.error('Error loading risk scores:', error);

      if (this.retryCount < this.maxRetries) {
        const delay = Utils.getBackoffDelay(this.retryCount, 5000, 60000);
        this.retryCount++;
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
            Loading risk scores... (retry ${this.retryCount}/${this.maxRetries})
          </div>
        `;
        setTimeout(() => { this.loaded = false; this.init(); }, delay);
      } else {
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
            Unable to load risk scores. <a href="#" onclick="RiskScoreMonitor.retryCount=0; RiskScoreMonitor.loaded=false; RiskScoreMonitor.init(); return false;">Retry</a>
          </div>
        `;
      }
    }
  },

  render(container, scores, isStale = false) {
    if (!scores || scores.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          No risk data available
        </div>
      `;
      return;
    }

    // Update hero stats counts
    const criticalCount = scores.filter(s => s.riskLevel === 'CRITICAL').length;
    const alertCount = scores.filter(s => s.riskLevel === 'ALERT').length;
    const warningCount = scores.filter(s => s.riskLevel === 'WARNING').length;

    const criticalEl = document.getElementById('ew-critical-count');
    const alertEl = document.getElementById('ew-alert-count');
    const warningEl = document.getElementById('ew-warning-count');

    if (criticalEl) criticalEl.textContent = criticalCount;
    if (alertEl) alertEl.textContent = alertCount;
    if (warningEl) warningEl.textContent = warningCount;

    // Render forecast cards (top 3 at-risk countries)
    this.renderForecastCards(scores);

    const staleIndicator = isStale ? `
      <div style="padding: 8px 16px; background: rgba(255, 159, 10, 0.1); border-radius: 8px; margin-bottom: 12px; font-size: 0.75rem; color: var(--warning);">
        ⟳ Data may be outdated, refreshing in background...
      </div>
    ` : '';

    const rows = scores.slice(0, 20).map(score => {
      const rowClass = this.getRowClass(score.riskLevel);
      const badgeClass = this.getBadgeClass(score.riskLevel);
      const scoreClass = this.getScoreClass(score.riskLevel);
      const confirmedBadge = score.elevatedCount >= 2 ? '<span class="confirmed-badge">2/3</span>' : '';

      // Horizon badge with color
      const horizonBadge = this.getHorizonBadge(score.horizon);

      const drivers = score.drivers && score.drivers.length > 0
        ? `<div class="drivers-list">${score.drivers.slice(0, 3).map(d =>
            `<span class="driver-chip">${d}</span>`
          ).join('')}</div>`
        : '';

      return `
        <tr class="${rowClass}">
          <td>
            <div class="country-cell">
              <strong>${score.countryName}</strong>
              ${drivers}
            </div>
          </td>
          <td class="value-cell">
            <span class="ew-score-big ${scoreClass}">${score.score}</span>
          </td>
          <td class="value-cell">
            <span class="badge ${badgeClass}">${score.riskLevel}</span>
            ${confirmedBadge}
          </td>
          <td class="value-cell">${horizonBadge}</td>
        </tr>
      `;
    }).join('');

    container.innerHTML = `
      ${staleIndicator}
      <table class="data-table">
        <thead>
          <tr>
            <th>Country / Drivers</th>
            <th>Score</th>
            <th>Level</th>
            <th>Horizon</th>
          </tr>
        </thead>
        <tbody>
          ${rows}
        </tbody>
      </table>
    `;
  },

  renderForecastCards(scores) {
    const forecastContainer = document.getElementById('ew-forecast-cards');
    if (!forecastContainer) return;

    // Get top 3 countries with CRITICAL, ALERT, or WARNING status
    const atRisk = scores
      .filter(s => ['CRITICAL', 'ALERT', 'WARNING'].includes(s.riskLevel))
      .slice(0, 3);

    if (atRisk.length === 0) {
      forecastContainer.innerHTML = `
        <div style="padding: var(--space-md); color: var(--text-secondary);">
          No imminent deterioration detected
        </div>
      `;
      return;
    }

    const cards = atRisk.map(score => {
      const levelClass = score.riskLevel === 'CRITICAL' ? '' :
                         score.riskLevel === 'ALERT' ? 'alert-level' : 'warning-level';
      const horizonBadge = this.getHorizonBadge(score.horizon);

      const drivers = score.drivers && score.drivers.length > 0
        ? score.drivers.slice(0, 3).map(d => `<span class="driver-chip">${d}</span>`).join('')
        : '';

      return `
        <div class="ew-forecast-card ${levelClass}">
          <div class="ew-forecast-score">${score.score}</div>
          <div class="ew-forecast-content">
            <div class="ew-forecast-country">${score.countryName}</div>
            <div class="ew-forecast-drivers">${drivers}</div>
            <div class="ew-forecast-meta">
              <span class="badge ${this.getBadgeClass(score.riskLevel)}">${score.riskLevel}</span>
              ${horizonBadge}
            </div>
          </div>
        </div>
      `;
    }).join('');

    forecastContainer.innerHTML = cards;
  },

  getHorizonBadge(horizon) {
    if (!horizon) return '<span class="text-muted">—</span>';

    const h = horizon.toLowerCase();
    if (h.includes('30') && !h.includes('60') && !h.includes('90')) {
      return `<span class="horizon-badge urgent">🔴 ${horizon}</span>`;
    } else if (h.includes('60') || h.includes('30-60')) {
      return `<span class="horizon-badge soon">🟠 ${horizon}</span>`;
    } else {
      return `<span class="horizon-badge extended">🟢 ${horizon}</span>`;
    }
  },

  getRowClass(level) {
    switch (level) {
      case 'CRITICAL': return 'row-critical';
      case 'ALERT': return 'row-alert';
      case 'WARNING': return 'row-warning';
      default: return '';
    }
  },

  getScoreClass(level) {
    switch (level) {
      case 'CRITICAL': return 'score-critical';
      case 'ALERT': return 'score-alert';
      case 'WARNING': return 'score-warning';
      default: return '';
    }
  },

  getBadgeClass(level) {
    switch (level) {
      case 'CRITICAL': return 'famine';
      case 'ALERT': return 'critical';
      case 'WARNING': return 'high';
      case 'WATCH': return 'medium';
      default: return 'stable';
    }
  }
};

// ============================================
// CLIMATE MONITORING (Open-Meteo)
// ============================================
const ClimateMonitor = {
  loaded: false,

  async init() {
    if (this.loaded) return;

    const container = document.getElementById('climate-container');
    if (!container) return;

    container.innerHTML = `
      <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
        <div class="loading-spinner"></div>
        Loading climate anomalies...
      </div>
    `;

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch('/api/precipitation/anomalies', { signal: controller.signal });
      clearTimeout(timeoutId);

      const result = await response.json();

      if (result.status === 'LOADING') {
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
            <div class="loading-spinner"></div>
            ${result.message || 'Loading climate data...'}
          </div>
        `;
        setTimeout(() => { this.loaded = false; this.init(); }, 10000);
        return;
      }

      const anomalies = result.data || result;
      this.render(container, anomalies, result.status === 'STALE');
      this.loaded = true;
    } catch (error) {
      console.error('Error loading climate data:', error);
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
          Loading climate data... <span class="loading-dots"></span>
        </div>
      `;
      setTimeout(() => { this.loaded = false; this.init(); }, 15000);
    }
  },

  render(container, anomalies, isStale = false) {
    if (!anomalies || anomalies.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          No climate data available
        </div>
      `;
      return;
    }

    const staleIndicator = isStale ? `
      <div style="padding: 8px 16px; background: rgba(255, 159, 10, 0.1); border-radius: 8px; margin-bottom: 12px; font-size: 0.75rem; color: var(--warning);">
        ⟳ Data may be outdated, refreshing in background...
      </div>
    ` : '';

    const rows = anomalies.slice(0, 18).map(a => {
      const rowClass = a.drought ? (a.severeDrought ? 'critical-row' : 'drought-row') : '';
      const anomalyClass = a.anomalyPercent < 0 ? 'anomaly-negative' : 'anomaly-positive';
      const sign = a.anomalyPercent > 0 ? '+' : '';

      return `
        <tr class="${rowClass}">
          <td>
            <strong>${a.countryName}</strong>
            <div class="text-tertiary" style="font-size: 0.7rem;">${a.locationName}</div>
          </td>
          <td class="value-cell ${anomalyClass}">
            ${sign}${a.anomalyPercent?.toFixed(0) || 0}%
          </td>
          <td class="value-cell">
            <span class="badge ${this.getBadgeClass(a.spiCategory)}">${a.riskLevel || 'N/A'}</span>
          </td>
          <td class="value-cell text-secondary">${a.currentPrecipMm?.toFixed(0) || 0} mm</td>
        </tr>
      `;
    }).join('');

    container.innerHTML = `
      ${staleIndicator}
      <table class="data-table">
        <thead>
          <tr>
            <th>Country / Location</th>
            <th>Anomaly</th>
            <th>Status</th>
            <th>30d Rain</th>
          </tr>
        </thead>
        <tbody>
          ${rows}
        </tbody>
      </table>
    `;
  },

  getBadgeClass(category) {
    if (!category) return '';
    if (category.includes('EXCEPTIONAL') || category.includes('EXTREME')) return 'critical';
    if (category.includes('SEVERE')) return 'high';
    if (category.includes('MODERATE') || category.includes('DRY')) return 'medium';
    if (category.includes('FLOOD') || category.includes('WET')) return 'medium';
    return 'stable';
  }
};

// ============================================
// CURRENCY MONITORING
// ============================================
const CurrencyMonitor = {
  loaded: false,

  async init() {
    if (this.loaded) return;

    const container = document.getElementById('currency-container');
    if (!container) return;

    container.innerHTML = `
      <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
        <div class="loading-spinner"></div>
        Loading currency data...
      </div>
    `;

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch('/api/currency/all', { signal: controller.signal });
      clearTimeout(timeoutId);

      const result = await response.json();

      if (result.status === 'LOADING') {
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
            <div class="loading-spinner"></div>
            ${result.message || 'Loading currency data...'}
          </div>
        `;
        setTimeout(() => { this.loaded = false; this.init(); }, 10000);
        return;
      }

      const currencies = result.data || result;
      this.render(container, currencies, result.status === 'STALE');
      this.loaded = true;
    } catch (error) {
      console.error('Error loading currency data:', error);
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
          Loading currency data... <span class="loading-dots"></span>
        </div>
      `;
      setTimeout(() => { this.loaded = false; this.init(); }, 15000);
    }
  },

  render(container, currencies, isStale = false) {
    if (!currencies || currencies.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          No currency data available
        </div>
      `;
      return;
    }

    const staleIndicator = isStale ? `
      <div style="padding: 8px 16px; background: rgba(255, 159, 10, 0.1); border-radius: 8px; margin-bottom: 12px; font-size: 0.75rem; color: var(--warning);">
        ⟳ Data may be outdated, refreshing in background...
      </div>
    ` : '';

    const rows = currencies.slice(0, 18).map(c => {
      const rowClass = c.trend === 'CRISIS' ? 'crisis-currency-row' : (c.trend === 'DEVALUING' ? 'critical-row' : '');
      const trendClass = c.change30d > 5 ? 'trend-up' : (c.change30d < -5 ? 'trend-down' : 'trend-stable');
      const sign = c.change30d > 0 ? '+' : '';
      const changeDisplay = c.change30d != null ? `${sign}${c.change30d.toFixed(1)}%` : '-';

      return `
        <tr class="${rowClass}">
          <td>
            <strong>${c.countryName}</strong>
            <div class="text-tertiary" style="font-size: 0.7rem;">${c.currencyCode}</div>
          </td>
          <td class="value-cell">${c.currentRate?.toFixed(2) || '-'}</td>
          <td class="value-cell ${trendClass}">${changeDisplay}</td>
          <td class="value-cell">
            <span class="badge ${this.getBadgeClass(c.trend)}">${c.trend || 'N/A'}</span>
          </td>
        </tr>
      `;
    }).join('');

    container.innerHTML = `
      ${staleIndicator}
      <table class="data-table">
        <thead>
          <tr>
            <th>Country / Currency</th>
            <th>Rate (USD)</th>
            <th>30d Chg</th>
            <th>Trend</th>
          </tr>
        </thead>
        <tbody>
          ${rows}
        </tbody>
      </table>
    `;
  },

  getBadgeClass(trend) {
    switch (trend) {
      case 'CRISIS': return 'famine';
      case 'DEVALUING': return 'critical';
      case 'WEAKENING': return 'high';
      case 'STABLE': return 'stable';
      default: return '';
    }
  }
};

// ============================================
// FEWS NET IPC MONITORING
// ============================================
const IPCMonitor = {
  loaded: false,

  async init() {
    if (this.loaded) return;

    const container = document.getElementById('ipc-alerts-container');
    if (!container) return;

    container.innerHTML = `
      <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
        <div class="loading-spinner"></div>
        Loading food security data...
      </div>
    `;

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 30000);

      const response = await fetch('/api/ipc/alerts', { signal: controller.signal });
      clearTimeout(timeoutId);

      const result = await response.json();

      if (result.status === 'LOADING') {
        container.innerHTML = `
          <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
            <div class="loading-spinner"></div>
            ${result.message || 'Loading food security data...'}
          </div>
        `;
        setTimeout(() => { this.loaded = false; this.init(); }, 10000);
        return;
      }

      const alerts = result.data || result;
      this.render(container, alerts, result.status === 'STALE');
      this.loaded = true;
    } catch (error) {
      console.error('Error loading IPC alerts:', error);
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-tertiary);">
          Loading food security data... <span class="loading-dots"></span>
        </div>
      `;
      setTimeout(() => { this.loaded = false; this.init(); }, 15000);
    }
  },

  render(container, alerts, isStale = false) {
    if (!alerts || alerts.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          No IPC data available
        </div>
      `;
      return;
    }

    const staleIndicator = isStale ? `
      <div style="padding: 8px 16px; background: rgba(255, 159, 10, 0.1); border-radius: 8px; margin-bottom: 12px; font-size: 0.75rem; color: var(--warning);">
        ⟳ Data may be outdated, refreshing in background...
      </div>
    ` : '';

    // Filter to critical alerts (Phase 3+) and sort by severity
    const critical = alerts
      .filter(a => a.ipcPhase >= 3.0)
      .sort((a, b) => b.ipcPhase - a.ipcPhase)
      .slice(0, 12);

    if (critical.length === 0) {
      container.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">
          No critical food security alerts
        </div>
      `;
      return;
    }

    const rows = critical.map(alert => {
      const rowClass = this.getRowClass(alert.ipcPhase);
      const badgeClass = this.getBadgeClass(alert.ipcPhase);
      const projDate = alert.projectionEnd ? alert.projectionEnd.substring(0, 7) : '-';

      return `
        <tr class="${rowClass}">
          <td>
            <strong>${alert.countryName}</strong>
            ${alert.region ? `<div class="text-tertiary" style="font-size: 0.7rem;">${alert.region}</div>` : ''}
          </td>
          <td class="value-cell">
            <span class="badge ${badgeClass}">Phase ${alert.ipcPhase}</span>
          </td>
          <td class="value-cell">${alert.phaseDescription}</td>
          <td class="value-cell text-secondary">${projDate}</td>
        </tr>
      `;
    }).join('');

    container.innerHTML = `
      ${staleIndicator}
      <table class="data-table">
        <thead>
          <tr>
            <th>Country</th>
            <th>Phase</th>
            <th>Status</th>
            <th>Proj.</th>
          </tr>
        </thead>
        <tbody>
          ${rows}
        </tbody>
      </table>
    `;
  },

  getRowClass(phase) {
    if (phase >= 5.0) return 'famine-row';
    if (phase >= 4.0) return 'critical-row';
    if (phase >= 3.0) return 'high-row';
    return '';
  },

  getBadgeClass(phase) {
    if (phase >= 5.0) return 'famine';
    if (phase >= 4.0) return 'critical';
    if (phase >= 3.0) return 'high';
    return 'medium';
  }
};

// ============================================
// OVERVIEW MANAGER (4 BLOCKS)
// ============================================
const OverviewManager = {
  loaded: false,
  lastUpdate: null,
  situationsCache: null,

  async init() {
    console.log('[Overview] init called');

    // Load all blocks in parallel (Focus card moved to Early Warning)
    try {
      await Promise.all([
        this.loadLiveNews(),
        this.loadRegionalPulse(),
        this.loadContextHeadlines(),
        this.loadTopSituations()
      ]);
      console.log('[Overview] all blocks loaded');
    } catch (error) {
      console.error('OverviewManager init error:', error);
    }

    // Setup navigation links and refresh button (only once)
    if (!this.loaded) {
      this.setupNavLinks();
      this.setupRefreshButton();
    }

    this.loaded = true;
  },

  setupRefreshButton() {
    const btn = document.getElementById('overview-refresh-btn');
    if (btn) {
      btn.addEventListener('click', async () => {
        btn.classList.add('spinning');
        await this.refresh();
        btn.classList.remove('spinning');
      });
    }
  },

  updateTimestamp() {
    const dateEl = document.getElementById('brief-date');
    if (dateEl) {
      const now = new Date();
      this.lastUpdate = now;
      const time = now.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
      const date = now.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
      dateEl.textContent = `${date} • Updated ${time}`;
      dateEl.classList.remove('stale');
    }

    // Check for staleness every minute
    if (!this.staleCheckInterval) {
      this.staleCheckInterval = setInterval(() => this.checkStale(), 60000);
    }
  },

  checkStale() {
    const dateEl = document.getElementById('brief-date');
    if (dateEl && this.isStale()) {
      dateEl.classList.add('stale');
    }
  },

  isStale() {
    if (!this.lastUpdate) return true;
    const elapsed = Date.now() - this.lastUpdate.getTime();
    return elapsed > 30 * 60 * 1000; // 30 minutes
  },

  // Refresh data (can be called manually or on interval)
  async refresh() {
    this.loaded = false;
    this.situationsCache = null;
    await this.init();
  },

  // Normalize situations response - handles WARMING_UP and various shapes
  normalizeSituations(result) {
    // Check for warming up or error status
    if (result.status === 'WARMING_UP' || result.status === 'ERROR') {
      return { situations: [], status: result.status, timestamp: result.timestamp };
    }

    // Extract situations array - could be in different places
    let situations = result.situations || result.data || [];

    // Make sure it's an array
    if (!Array.isArray(situations)) situations = [];

    return { situations, status: result.status || 'OK', timestamp: result.timestamp };
  },

  // Normalize type for matching (backend uses CONFLICT, FOOD_SECURITY, etc.)
  matchType(type, pattern) {
    if (!type) return false;
    const normalized = type.toLowerCase().replace(/_/g, ' ');
    return normalized.includes(pattern.toLowerCase());
  },

  // Load Focus Now card with top deterioration risk
  async loadFocusNow() {
    const container = document.getElementById('focus-now-card');
    if (!container) {
      console.log('[Overview] Focus Now: container not found');
      return;
    }

    try {
      console.log('[Overview] Focus Now: fetching /api/risk/scores');
      const response = await fetch('/api/risk/scores');

      if (!response.ok) {
        console.error('[Overview] Focus Now: HTTP error', response.status);
        container.innerHTML = '<div class="focus-loading">Risk data temporarily unavailable</div>';
        return;
      }

      const result = await response.json();
      console.log('[Overview] Focus Now: response status:', result.status, 'data type:', typeof result.data);

      // Check if still warming up
      if (result.status === 'WARMING_UP') {
        container.innerHTML = '<div class="focus-loading">Risk data warming up...</div>';
        setTimeout(() => this.loadFocusNow(), 10000);
        return;
      }

      // Handle both array and {status, data: [...]} formats
      let scores = [];
      if (Array.isArray(result)) {
        scores = result;
      } else if (result.data && Array.isArray(result.data)) {
        scores = result.data;
      }
      console.log('[Overview] Focus Now: scores count:', scores.length);

      if (scores.length > 0) {
        // Get top risk country
        const top = scores[0];
        const drivers = (top.drivers || []).slice(0, 3);
        console.log('[Overview] Focus Now: top country:', top.countryName, 'score:', top.score);

        container.innerHTML = `
          <div class="focus-now-label">Top Deterioration Risk</div>
          <div class="focus-now-content">
            <div class="focus-now-main">
              <div class="focus-now-country">${top.countryName || 'Unknown'}</div>
              <div class="focus-now-drivers">
                ${drivers.map(d => `<span class="focus-driver-tag">${d}</span>`).join('')}
              </div>
            </div>
            <div class="focus-now-score">
              <span class="focus-score-value">${top.score || 0}</span>
              <span class="focus-score-level">${top.riskLevel || 'HIGH'}</span>
            </div>
            <div class="focus-now-horizon">${top.horizon || '30-day'} horizon</div>
          </div>
        `;
      } else {
        console.log('[Overview] Focus Now: no scores in response');
        container.innerHTML = '<div class="focus-loading">No risk data available — try refresh</div>';
      }
    } catch (error) {
      console.error('[Overview] Focus Now error:', error);
      container.innerHTML = '<div class="focus-loading">Unable to load risk data — try refresh</div>';
    }
  },

  async loadBriefBullets() {
    const container = document.getElementById('brief-bullets');
    if (!container) return;

    try {
      const response = await fetch('/api/situations/active');
      const result = await response.json();
      const { situations, status } = this.normalizeSituations(result);

      // Cache for other methods
      this.situationsCache = { situations, status, fetchedAt: Date.now() };

      if (status === 'WARMING_UP') {
        container.innerHTML = '<div class="brief-bullet warming">System warming up, data loading...</div>';
        // Retry in 15s
        setTimeout(() => { this.loaded = false; this.loadBriefBullets(); }, 15000);
        return;
      }

      if (situations.length > 0) {
        const critical = situations.filter(s => s.severity === 'CRITICAL');
        const high = situations.filter(s => s.severity === 'HIGH');

        const bullets = [];
        // Use countryName (API field) or country as fallback
        const getCountry = s => s.countryName || s.country || 'Unknown';
        const getType = s => s.situationLabel || s.situationType || s.type || '';

        if (critical.length > 0) {
          bullets.push(`${critical.length} critical situation${critical.length > 1 ? 's' : ''}: ${critical.map(getCountry).join(', ')}`);
        }
        if (high.length > 0) {
          bullets.push(`${high.length} high-priority watch: ${high.slice(0, 3).map(getCountry).join(', ')}`);
        }

        // Add a driver-based bullet (use matchType for flexibility)
        const conflictCount = situations.filter(s => this.matchType(getType(s), 'conflict') || this.matchType(getType(s), 'violence')).length;
        const foodCount = situations.filter(s => this.matchType(getType(s), 'food') || this.matchType(getType(s), 'famine')).length;
        if (conflictCount > foodCount && conflictCount > 0) {
          bullets.push(`Conflict-driven crises dominating (${conflictCount} situations)`);
        } else if (foodCount > 0) {
          bullets.push(`Food security remains critical driver (${foodCount} situations)`);
        }

        if (bullets.length === 0) {
          bullets.push('Monitoring active - no critical escalation detected');
        }

        container.innerHTML = bullets.map(b => `<div class="brief-bullet">${b}</div>`).join('');
      } else {
        container.innerHTML = '<div class="brief-bullet">Monitoring active — no escalations detected</div>';
      }
    } catch (error) {
      container.innerHTML = '<div class="brief-bullet">Summary temporarily unavailable</div>';
    }
  },

  async loadTopSituations() {
    const container = document.getElementById('overview-top-situations');
    if (!container) return;

    try {
      // Try Claude cache first (more accurate)
      let situations = [];
      let status = 'OK';

      const claudeResponse = await fetch('/api/situations/claude-cached', { cache: 'no-store' });
      if (claudeResponse.ok) {
        const claudeData = await claudeResponse.json();
        if (claudeData && claudeData.status === 'OK' && claudeData.situations && claudeData.situations.length > 0) {
          situations = claudeData.situations;
          console.log('[Overview] Using Claude cached situations:', situations.length);
        }
      }

      // Fall back to keyword-based if Claude cache empty
      if (situations.length === 0) {
        const response = await fetch('/api/situations/active');
        const result = await response.json();
        const normalized = this.normalizeSituations(result);
        situations = normalized.situations;
        status = normalized.status;
      }

      if (status === 'WARMING_UP') {
        container.innerHTML = '<div class="loading-placeholder">Warming up...</div>';
        return;
      }

      const filtered = situations
        .filter(s => s.severity === 'CRITICAL' || s.severity === 'HIGH')
        .slice(0, 3);

      if (filtered.length > 0) {
        container.innerHTML = filtered.map(s => {
          const country = s.countryName || s.country || 'Unknown';
          const type = s.situationLabel || s.situationType || s.type || 'Multiple';
          const summary = s.summary || '';
          return `
            <div class="top-situation-item">
              <span class="situation-severity ${(s.severity || '').toLowerCase()}"></span>
              <span class="situation-country">${country}</span>
              <span class="situation-type">${type}</span>
              <span class="situation-drivers">${summary.substring(0, 60)}${summary.length > 60 ? '...' : ''}</span>
            </div>
          `;
        }).join('');
      } else {
        container.innerHTML = '<div class="loading-placeholder" style="color: var(--text-muted); font-size: 0.85rem;">No active situations detected</div>';
      }
    } catch (error) {
      container.innerHTML = '<div class="loading-placeholder" style="color: var(--text-muted); font-size: 0.85rem;">Situation detection unavailable</div>';
    }
  },

  // Load live news from GDELT + RSS for "What's Happening Now"
  async loadLiveNews() {
    const container = document.getElementById('live-news-grid');
    if (!container) {
      console.log('[Overview] Live News: container not found');
      return;
    }

    try {
      console.log('[Overview] Live News: fetching /api/stories');
      const response = await fetch('/api/stories?days=1');

      if (!response.ok) {
        console.error('[Overview] Live News: HTTP error', response.status);
        container.innerHTML = '<div class="loading-placeholder">News feed temporarily unavailable</div>';
        return;
      }

      const stories = await response.json();
      console.log('[Overview] Live News: got', stories.length, 'stories');

      if (!stories || stories.length === 0) {
        container.innerHTML = '<div class="loading-placeholder">No news stories available</div>';
        return;
      }

      // Take top 6 stories for the grid
      const topStories = stories.slice(0, 6);

      container.innerHTML = topStories.map(story => {
        const regionBadge = story.region && story.region !== 'Global'
          ? `<span class="news-region-badge">${story.region}</span>`
          : '';
        const topicBadge = story.topicTags && story.topicTags.length > 0
          ? `<span class="news-topic-badge">${story.topicTags[0]}</span>`
          : '';
        const sourcesText = story.sources && story.sources.length > 0
          ? story.sources.slice(0, 2).join(', ')
          : 'Multiple sources';
        const countryText = story.countryNames && story.countryNames.length > 0
          ? story.countryNames[0]
          : '';

        return `
          <div class="live-news-card" data-story-id="${story.id || ''}">
            <div class="live-news-badges">
              ${regionBadge}
              ${topicBadge}
            </div>
            <div class="live-news-title">${story.title || 'Untitled'}</div>
            <div class="live-news-meta">
              ${countryText ? `<span class="live-news-country">${countryText}</span>` : ''}
              <span class="live-news-sources">${sourcesText}</span>
              <span class="live-news-volume">${story.volume24h || 1} articles</span>
            </div>
          </div>
        `;
      }).join('');

      // Setup clicks on news cards
      this.setupLiveNewsClicks();
    } catch (error) {
      console.error('[Overview] Live News error:', error);
      container.innerHTML = '<div class="loading-placeholder">Unable to load news — try refresh</div>';
    }
  },

  setupLiveNewsClicks() {
    document.querySelectorAll('.live-news-card').forEach(card => {
      card.addEventListener('click', () => {
        // Navigate to News Feed section
        SidebarManager.switchSection('news-feed');
      });
    });
  },

  async loadRegionalPulse() {
    const container = document.getElementById('regional-pulse-grid');
    if (!container) {
      console.log('[Overview] Regional Pulse: container not found');
      return;
    }

    try {
      console.log('[Overview] Regional Pulse: fetching /api/regions/pulse');
      const response = await fetch('/api/regions/pulse');
      const pulse = await response.json();
      console.log('[Overview] Regional Pulse: response', pulse);

      if (!pulse || !pulse.regions || pulse.regions.length === 0) {
        container.innerHTML = '<div class="loading-placeholder">Regional data loading...</div>';
        return;
      }

      container.innerHTML = pulse.regions.map(region => {
        const hasHotspot = region.hotspot1Name;
        const levelClass = (region.hotspot1Level || 'high').toLowerCase();

        return `
          <div class="pulse-card" data-region="${region.regionCode}">
            <div class="pulse-header">
              <span class="pulse-region-name">${region.regionName}</span>
              <div class="pulse-counts">
                ${region.criticalCount > 0 ? `<span class="pulse-count critical">${region.criticalCount}</span>` : ''}
                ${region.highCount > 0 ? `<span class="pulse-count high">${region.highCount}</span>` : ''}
              </div>
            </div>
            ${hasHotspot ? `
              <div class="pulse-hotspot">
                <span class="pulse-hotspot-name">${region.hotspot1Name}</span>
                <span class="pulse-hotspot-score ${levelClass}">${region.hotspot1Score}</span>
              </div>
              ${region.hotspot2Name ? `
                <div class="pulse-hotspot secondary">
                  <span class="pulse-hotspot-name">${region.hotspot2Name}</span>
                  <span class="pulse-hotspot-score">${region.hotspot2Score}</span>
                </div>
              ` : ''}
              ${region.dominantDriver ? `<div class="pulse-driver">${region.dominantDriver}</div>` : ''}
            ` : `
              <div class="pulse-no-data">Stable — no critical situations</div>
            `}
            <div class="pulse-card-actions">
              <button class="pulse-drilldown-btn" data-region="${region.regionCode}" title="View all countries in ${region.regionName}">
                View all
              </button>
              <button class="pulse-brief-btn" data-region="${region.regionCode}" title="Get AI briefing for ${region.regionName}">
                Brief me
              </button>
            </div>
          </div>
        `;
      }).join('');

      // Setup clicks on pulse cards
      this.setupRegionalPulseClicks();
    } catch (error) {
      console.error('[Overview] Regional pulse error:', error);
      container.innerHTML = '<div class="loading-placeholder">Regional data temporarily unavailable</div>';
    }
  },

  async loadContextHeadlines(region = null) {
    const container = document.getElementById('context-headlines');
    if (!container) {
      console.log('[Overview] Context Headlines: container not found');
      return;
    }

    try {
      // Fetch all or region-specific context
      const url = region
        ? `/api/regions/context?region=${encodeURIComponent(region)}`
        : '/api/regions/context/all';
      console.log('[Overview] Context: fetching', url);
      const response = await fetch(url);
      const headlines = await response.json();
      console.log('[Overview] Context: got', headlines.length, 'headlines for', region || 'all');

      if (!headlines || headlines.length === 0) {
        container.innerHTML = '<div class="loading-placeholder">Context signals loading...</div>';
        return;
      }

      container.innerHTML = headlines.map(item => {
        // Sanitize URL: only allow http/https
        const safeUrl = Utils.sanitizeUrl(item.url, null);
        return `
          <div class="context-item ${item.type === 'Humanitarian' ? 'humanitarian' : 'media'}">
            <div class="context-meta">
              <span class="context-source">${Utils.escapeHtml(item.source)}</span>
              <span class="context-region">${Utils.escapeHtml(item.region)}</span>
              ${item.timestamp ? `<span class="context-time">${Utils.escapeHtml(item.timestamp)}</span>` : ''}
            </div>
            ${safeUrl
              ? `<a href="${safeUrl}" target="_blank" rel="noopener noreferrer" class="context-title">${Utils.escapeHtml(item.title)}</a>`
              : `<span class="context-title">${Utils.escapeHtml(item.title)}</span>`
            }
          </div>
        `;
      }).join('');
    } catch (error) {
      console.error('[Overview] Context headlines error:', error);
      container.innerHTML = '<div class="loading-placeholder">Context signals temporarily unavailable</div>';
    }
  },

  setupNavLinks() {
    document.querySelectorAll('.block-link[data-nav]').forEach(link => {
      link.addEventListener('click', (e) => {
        e.preventDefault();
        const section = link.dataset.nav;
        const btn = document.querySelector(`.sidebar-item[data-section="${section}"]`);
        if (btn) btn.click();
      });
    });
  },

  selectedRegion: null,

  setupRegionalPulseClicks() {
    // Card click - open regional drill-down
    document.querySelectorAll('.pulse-card').forEach(card => {
      card.addEventListener('click', (e) => {
        // Don't trigger card click if clicking the brief button
        if (e.target.classList.contains('pulse-brief-btn')) return;

        const regionCode = card.dataset.region;
        if (!regionCode) return;

        // Toggle selection for context headlines
        if (this.selectedRegion === regionCode) {
          // Deselect - show all regions
          this.selectedRegion = null;
          document.querySelectorAll('.pulse-card').forEach(c => c.classList.remove('selected'));
          this.loadContextHeadlines(); // Load all
        } else {
          // Select this region
          this.selectedRegion = regionCode;
          document.querySelectorAll('.pulse-card').forEach(c => c.classList.remove('selected'));
          card.classList.add('selected');
          this.loadContextHeadlines(regionCode); // Load region-specific
        }

        console.log('[Overview] Selected region:', this.selectedRegion);
      });
    });

    // Brief button click - get AI regional briefing
    document.querySelectorAll('.pulse-brief-btn').forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const regionCode = btn.dataset.region;
        if (!regionCode) return;
        await this.getRegionalBrief(regionCode);
      });
    });

    // Drill-down button click - open region detail in modal
    document.querySelectorAll('.pulse-drilldown-btn').forEach(btn => {
      btn.addEventListener('click', async (e) => {
        e.stopPropagation();
        const regionCode = btn.dataset.region;
        if (!regionCode) return;
        await this.openRegionDetail(regionCode);
      });
    });
  },

  async openRegionDetail(regionCode) {
    const modal = document.getElementById('country-modal');
    const title = document.getElementById('country-modal-title');
    const body = document.getElementById('country-modal-body');
    if (!modal || !body) return;

    modal.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    title.textContent = 'Regional Detail';
    body.innerHTML = `<div style="text-align:center;padding:40px;"><div class="loading-spinner"></div><div style="margin-top:12px;color:var(--text-secondary)">Loading regional data...</div></div>`;

    try {
      const res = await fetch(`/api/regions/${encodeURIComponent(regionCode)}/detail`);
      const d = res.ok ? await res.json() : null;
      if (!d) { body.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-tertiary)">No regional data available.</div>'; return; }

      title.textContent = d.regionName || regionCode;

      let html = `<div class="country-detail-content">`;

      // Summary bar
      html += `<div class="cp-header">
        <div class="cp-score-badge medium"><span class="cp-score-num">${d.avgScore}</span><span class="cp-score-label">AVG</span></div>
        <div class="cp-header-info">
          <span class="cp-region">${d.countries ? d.countries.length : 0} countries monitored</span>
          <span class="cp-trend trend-stable">${d.criticalCount} critical, ${d.alertCount} alert, ${d.warningCount} warning</span>
        </div>
      </div>`;

      // Driver mix
      if (d.driverMix && d.driverMix.length) {
        html += `<div class="country-section"><h4>Dominant Drivers</h4><div class="cp-drivers">`;
        for (const dm of d.driverMix) {
          html += `<div class="cp-driver-row">
            <span class="cp-driver-label">${Utils.escapeHtml(dm.driver)}</span>
            <div class="cp-driver-bar-bg"><div class="cp-driver-bar" style="width:${dm.percent}%;background:var(--status-medium)"></div></div>
            <span class="cp-driver-score">${dm.count}</span>
          </div>`;
        }
        html += `</div></div>`;
      }

      // Country ranking table
      if (d.countries && d.countries.length) {
        html += `<div class="country-section"><h4>Country Ranking</h4><div class="rd-table">`;
        for (const c of d.countries) {
          const lvlCls = c.riskLevel === 'CRITICAL' ? 'critical' : c.riskLevel === 'ALERT' ? 'high' : c.riskLevel === 'WARNING' ? 'medium' : 'low';
          const trendCls = c.trend === 'rising' ? 'trend-up' : c.trend === 'falling' ? 'trend-down' : 'trend-stable';
          html += `<div class="rd-row" data-iso3="${c.iso3}" style="cursor:pointer">
            <span class="rd-score ${lvlCls}">${c.score}</span>
            <span class="rd-name">${Utils.escapeHtml(c.name || c.iso3)}</span>
            <span class="rd-trend ${trendCls}">${Utils.escapeHtml(c.trendIcon || '')}${c.scoreDelta != null ? ` ${c.scoreDelta > 0 ? '+' : ''}${c.scoreDelta}` : ''}</span>
            <span class="rd-driver">${Utils.escapeHtml(c.topDriver || '')}</span>
          </div>`;
        }
        html += `</div></div>`;
      }

      html += `</div>`;
      body.innerHTML = html;

      // Click on country row → open country profile
      body.querySelectorAll('.rd-row[data-iso3]').forEach(row => {
        row.addEventListener('click', () => {
          const iso3 = row.dataset.iso3;
          modal.classList.add('hidden');
          document.body.style.overflow = '';
          CountryDetailManager.open(iso3);
        });
      });
    } catch (err) {
      console.error('Region detail error:', err);
      body.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-tertiary)">Failed to load regional data.</div>';
    }
  },

  async getRegionalBrief(regionCode) {
    // Show loading modal
    const modal = document.createElement('div');
    modal.className = 'regional-brief-modal';
    modal.innerHTML = `
      <div class="regional-brief-content">
        <div class="regional-brief-header">
          <h3>Regional Intelligence Brief: ${regionCode.toUpperCase()}</h3>
          <button class="regional-brief-close">&times;</button>
        </div>
        <div class="regional-brief-body">
          <div class="loading-spinner"></div>
          <p>Generating intelligence brief...</p>
        </div>
      </div>
    `;
    document.body.appendChild(modal);

    // Close button handler
    modal.querySelector('.regional-brief-close').addEventListener('click', () => {
      modal.remove();
    });

    // Click outside to close
    modal.addEventListener('click', (e) => {
      if (e.target === modal) modal.remove();
    });

    try {
      const response = await fetch(`/api/analysis/region?region=${encodeURIComponent(regionCode)}`);
      const analysis = await response.json();

      const briefContent = analysis.summary || analysis.keyFindings?.[0] || 'No brief available';

      modal.querySelector('.regional-brief-body').innerHTML = `
        <div class="regional-brief-text">${briefContent.replace(/\n/g, '<br>')}</div>
        <div class="regional-brief-meta">
          <span>Generated: ${new Date(analysis.generatedAt).toLocaleString()}</span>
          <span>Model: ${analysis.model || 'Claude'}</span>
        </div>
      `;
    } catch (error) {
      console.error('[Overview] Regional brief error:', error);
      modal.querySelector('.regional-brief-body').innerHTML = `
        <div class="regional-brief-error">Failed to generate brief. Please try again.</div>
      `;
    }
  }
};

// ============================================
// REGIONAL CLUSTER ALERTS
// ============================================
const ClusterAlertMonitor = {
  loaded: false,

  async init() {
    if (this.loaded) return;

    const container = document.getElementById('cluster-alerts-container');
    if (!container) return;

    try {
      const response = await fetch('/api/clusters');
      if (!response.ok) throw new Error('Failed to load cluster alerts');

      const clusters = await response.json();
      this.render(container, clusters);
      this.loaded = true;
    } catch (error) {
      console.log('Cluster alerts loading silently failed:', error.message);
      container.innerHTML = '';
    }
  },

  render(container, clusters, showOnlyActive = true) {
    if (!clusters || clusters.length === 0) {
      container.innerHTML = '';
      return;
    }

    // Filter to show only clusters with critical/high status in overview
    const filtered = showOnlyActive
      ? clusters.filter(c => c.status === 'CRITICAL CLUSTER' || c.status === 'HIGH RISK CLUSTER')
      : clusters;

    if (filtered.length === 0) {
      container.innerHTML = '';
      return;
    }

    const alertsHtml = filtered.map(cluster => {
      const statusClass = cluster.status === 'CRITICAL CLUSTER' ? 'cluster-critical' :
                          cluster.status === 'HIGH RISK CLUSTER' ? 'cluster-high' : 'cluster-warning';

      const driversHtml = cluster.topDrivers && cluster.topDrivers.length > 0
        ? `<span class="cluster-drivers">${cluster.topDrivers.join(' + ')}</span>`
        : '';

      return `
        <div class="cluster-alert ${statusClass}">
          <div class="cluster-header">
            <span class="cluster-icon">${cluster.statusIcon}</span>
            <span class="cluster-name">${cluster.clusterName}</span>
            <span class="cluster-status">${cluster.status}</span>
          </div>
          <div class="cluster-body">
            <span class="cluster-desc">${cluster.description}</span>
            ${driversHtml}
          </div>
          <div class="cluster-countries">
            ${cluster.affectedCountries ? cluster.affectedCountries.join(', ') : ''}
          </div>
        </div>
      `;
    }).join('');

    container.innerHTML = `
      <div class="cluster-alerts-strip">
        <div class="cluster-alerts-title">Regional Convergence</div>
        <div class="cluster-alerts-list">${alertsHtml}</div>
      </div>
    `;
  },

  async loadFull() {
    const container = document.getElementById('cluster-alerts-full');
    if (!container) return;

    try {
      const response = await fetch('/api/clusters');
      if (!response.ok) throw new Error('Failed to load cluster alerts');

      const clusters = await response.json();
      this.renderCompact(container, clusters);
    } catch (error) {
      console.log('Full cluster alerts loading silently failed:', error.message);
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">Unable to load regional cluster data</div>';
    }
  },

  renderCompact(container, clusters) {
    if (!clusters || clusters.length === 0) {
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">No regional alerts</div>';
      return;
    }

    const alertsHtml = clusters.map(cluster => {
      const statusClass = cluster.status === 'CRITICAL CLUSTER' ? 'cluster-critical' :
                          cluster.status === 'HIGH RISK CLUSTER' ? 'cluster-high' : 'cluster-warning';

      const countries = cluster.affectedCountries && cluster.affectedCountries.length > 0
        ? cluster.affectedCountries.join(', ')
        : '';

      // Show sync badge for clusters with 2+ countries at risk
      const hasSync = (cluster.criticalCount + cluster.alertCount) >= 2;
      const syncBadge = hasSync
        ? `<div class="ew-cluster-sync">⚡ Synchronized deterioration (30d)</div>`
        : '';

      // Show drivers
      const drivers = cluster.topDrivers && cluster.topDrivers.length > 0
        ? cluster.topDrivers.slice(0, 2).join(' + ')
        : '';

      return `
        <div class="ew-cluster-card ${statusClass}">
          <div class="ew-cluster-icon">${cluster.statusIcon}</div>
          <div class="ew-cluster-content">
            <div class="ew-cluster-name">${cluster.clusterName}</div>
            <div class="ew-cluster-desc">${cluster.description}${drivers ? ` — ${drivers}` : ''}</div>
            ${countries ? `<div class="ew-cluster-countries">${countries}</div>` : ''}
            ${syncBadge}
          </div>
        </div>
      `;
    }).join('');

    container.innerHTML = alertsHtml;
  }
};

// ============================================
// DATA FRESHNESS MONITOR
// ============================================

// ============================================
// FOCUS ADVISOR - "Where should I look?"
// ============================================
const FocusAdvisor = {
  isLoading: false,
  lastResult: null,

  init() {
    const btn = document.getElementById('where-to-look-btn');
    if (!btn) return;

    btn.addEventListener('click', () => this.analyze());
  },

  async analyze() {
    if (this.isLoading) return;

    const btn = document.getElementById('where-to-look-btn');
    if (!btn) return;

    this.isLoading = true;
    const originalText = btn.innerHTML;
    btn.innerHTML = `
      <span class="loading-spinner" style="width: 14px; height: 14px; margin-right: 4px;"></span>
      Analyzing...
    `;
    btn.disabled = true;

    try {
      // Use the global AI analysis endpoint
      const response = await fetch('/api/analysis/global');
      if (!response.ok) throw new Error('Analysis failed');

      const data = await response.json();
      this.lastResult = data;
      this.showResult(data);

    } catch (error) {
      console.error('Focus analysis error:', error);
      Toast.error('Unable to analyze focus areas. Try again.');
    } finally {
      this.isLoading = false;
      btn.innerHTML = originalText;
      btn.disabled = false;
    }
  },

  showResult(data) {
    // Create a popup/tooltip with the focus recommendation
    const existing = document.getElementById('focus-advisor-popup');
    if (existing) existing.remove();

    const watchlist = data.watchlist || [];
    const topPriority = watchlist.slice(0, 3);

    if (topPriority.length === 0) {
      Toast.info('No immediate focus areas identified.');
      return;
    }

    const popup = document.createElement('div');
    popup.id = 'focus-advisor-popup';
    popup.className = 'focus-advisor-popup';
    popup.innerHTML = `
      <div class="focus-popup-header">
        <span>🎯 Priority Focus Areas</span>
        <button class="focus-popup-close" onclick="this.parentElement.parentElement.remove()">&times;</button>
      </div>
      <div class="focus-popup-content">
        ${topPriority.map((item, i) => `
          <div class="focus-item" data-iso3="${item.iso3 || ''}" data-country="${item.country || item}">
            <span class="focus-rank">${i + 1}</span>
            <span class="focus-country">${Utils.escapeHtml(typeof item === 'string' ? item : item.country || item.name || 'Unknown')}</span>
            ${item.reason ? `<span class="focus-reason">${Utils.escapeHtml(item.reason)}</span>` : ''}
          </div>
        `).join('')}
      </div>
      <div class="focus-popup-footer">
        <button class="btn-secondary" onclick="AIAnalysisManager.open(); document.getElementById('focus-advisor-popup')?.remove();">
          Full Analysis
        </button>
      </div>
    `;

    // Position near the button
    const btn = document.getElementById('where-to-look-btn');
    const rect = btn.getBoundingClientRect();
    popup.style.cssText = `
      position: fixed;
      top: ${rect.bottom + 8}px;
      right: ${window.innerWidth - rect.right}px;
      z-index: 1000;
    `;

    document.body.appendChild(popup);

    // Click on focus items to open country detail
    popup.querySelectorAll('.focus-item[data-iso3]').forEach(item => {
      item.style.cursor = 'pointer';
      item.addEventListener('click', () => {
        const iso3 = item.dataset.iso3;
        const country = item.dataset.country;
        if (iso3 && CountryDetailManager) {
          CountryDetailManager.open(iso3, country);
          popup.remove();
        }
      });
    });

    // Auto-close after 15 seconds
    setTimeout(() => popup.remove(), 15000);
  }
};

// ============================================
// AI ANALYSIS MANAGER
// ============================================
const AIAnalysisManager = {
  currentType: 'global',
  isLoading: false,
  lastAnalysisTime: 0,
  cooldownMs: 5000, // 5 second cooldown between analyses

  init() {
    const fab = document.getElementById('ai-fab');
    const aiBriefBtn = document.getElementById('ai-brief-btn');
    const modal = document.getElementById('ai-modal');
    const backdrop = modal?.querySelector('.ai-modal-backdrop');
    const closeBtn = document.getElementById('ai-modal-close');
    const typeBtns = document.querySelectorAll('.ai-type-btn');
    const analyzeBtn = document.getElementById('analyze-btn');
    const askBtn = document.getElementById('ask-btn');
    const questionInput = document.getElementById('question-input');

    if (!modal) return;

    // Open modal from FAB button
    fab?.addEventListener('click', () => {
      modal.classList.remove('hidden');
      Haptics.medium();
    });

    // Open modal from AI Brief button in navbar
    aiBriefBtn?.addEventListener('click', () => {
      modal.classList.remove('hidden');
      Haptics.medium();
      // Auto-trigger global analysis if results not shown
      const resultsContainer = document.getElementById('ai-results');
      if (resultsContainer?.classList.contains('hidden')) {
        this.analyze();
      }
    });

    // Load priority watch on page load
    this.loadPriorityWatch();

    // Close modal
    closeBtn?.addEventListener('click', () => this.closeModal());
    backdrop?.addEventListener('click', () => this.closeModal());

    // ESC to close
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !modal.classList.contains('hidden')) {
        this.closeModal();
      }
    });

    // Type selector
    typeBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        typeBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        this.currentType = btn.dataset.type;
        this.updateUI();
        Haptics.light();
      });
    });

    // Analyze button
    analyzeBtn?.addEventListener('click', () => this.analyze());

    // Ask button (for custom questions)
    askBtn?.addEventListener('click', () => this.analyze());

    // Enter key for custom question
    questionInput?.addEventListener('keypress', (e) => {
      if (e.key === 'Enter') this.analyze();
    });
  },

  closeModal() {
    const modal = document.getElementById('ai-modal');
    modal?.classList.add('hidden');
    // Reset results
    document.getElementById('ai-results')?.classList.add('hidden');
  },

  updateUI() {
    const countrySelector = document.getElementById('country-selector');
    const customQuestion = document.getElementById('custom-question');
    const analyzeBtn = document.getElementById('analyze-btn');

    // Hide all
    countrySelector?.classList.add('hidden');
    customQuestion?.classList.add('hidden');

    // Show based on type
    switch (this.currentType) {
      case 'country':
        countrySelector?.classList.remove('hidden');
        analyzeBtn.querySelector('.btn-text').textContent = 'Analyze Country';
        break;
      case 'custom':
        customQuestion?.classList.remove('hidden');
        analyzeBtn.querySelector('.btn-text').textContent = 'Ask Question';
        break;
      default:
        analyzeBtn.querySelector('.btn-text').textContent = 'Analyze Global';
    }
  },

  async analyze() {
    if (this.isLoading) return;

    // Rate limiting: enforce cooldown between analyses
    const now = Date.now();
    const timeSinceLastAnalysis = now - this.lastAnalysisTime;
    if (timeSinceLastAnalysis < this.cooldownMs) {
      const remainingSeconds = Math.ceil((this.cooldownMs - timeSinceLastAnalysis) / 1000);
      Toast.info(`Please wait ${remainingSeconds}s before next analysis`);
      return;
    }

    const analyzeBtn = document.getElementById('analyze-btn');
    const btnText = analyzeBtn.querySelector('.btn-text');
    const btnLoading = analyzeBtn.querySelector('.btn-loading');
    const resultsContainer = document.getElementById('ai-results');

    // Validate inputs
    if (this.currentType === 'country') {
      const countrySelect = document.getElementById('country-select');
      if (!countrySelect.value) {
        alert('Please select a country');
        return;
      }
    }

    if (this.currentType === 'custom') {
      const questionInput = document.getElementById('question-input');
      if (!questionInput.value.trim()) {
        alert('Please enter a question');
        return;
      }
    }

    // Show loading state
    this.isLoading = true;
    btnText.classList.add('hidden');
    btnLoading.classList.remove('hidden');
    analyzeBtn.disabled = true;

    // Show loading overlay in results area
    if (resultsContainer) {
      // Remove any existing loading overlay
      const existingOverlay = resultsContainer.querySelector('.ai-loading-overlay');
      if (existingOverlay) existingOverlay.remove();

      // Add loading overlay
      const loadingOverlay = document.createElement('div');
      loadingOverlay.className = 'ai-loading-overlay';
      loadingOverlay.innerHTML = `
        <div class="loading-spinner"></div>
        <div style="margin-top: 12px; color: var(--text-secondary);">
          Analyzing with Claude AI...
        </div>
      `;
      loadingOverlay.style.cssText = `
        position: absolute; top: 0; left: 0; right: 0; bottom: 0;
        display: flex; flex-direction: column; align-items: center; justify-content: center;
        background: var(--bg-secondary); border-radius: 12px; z-index: 10;
      `;
      resultsContainer.style.position = 'relative';
      resultsContainer.classList.remove('hidden');
      resultsContainer.appendChild(loadingOverlay);
      resultsContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }

    try {
      let data;
      let response;

      switch (this.currentType) {
        case 'global':
          response = await fetch('/api/analysis/global');
          break;
        case 'country':
          const countryCode = document.getElementById('country-select').value;
          response = await fetch(`/api/analysis/country?iso3=${countryCode}`);
          break;
        case 'custom':
          const question = document.getElementById('question-input').value;
          response = await fetch('/api/analysis/custom', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question })
          });
          break;
      }

      if (!response.ok) {
        throw new Error(`Server error: ${response.status}`);
      }

      data = await response.json();
      this.renderResults(data);

    } catch (error) {
      console.error('AI Analysis error:', error);
      this.renderError(error.message);
    } finally {
      this.isLoading = false;
      this.lastAnalysisTime = Date.now(); // Rate limiting timestamp
      btnText.classList.remove('hidden');
      btnLoading.classList.add('hidden');
      analyzeBtn.disabled = false;
    }
  },

  renderResults(data) {
    const resultsContainer = document.getElementById('ai-results');
    const findingsList = document.getElementById('findings-list');
    const driversList = document.getElementById('drivers-list');
    const watchlistList = document.getElementById('watchlist-list');
    const customAnswer = document.getElementById('custom-answer');
    const resultModel = document.getElementById('result-model');
    const resultTime = document.getElementById('result-time');

    // Remove loading overlay
    const loadingOverlay = resultsContainer?.querySelector('.ai-loading-overlay');
    if (loadingOverlay) loadingOverlay.remove();

    // Clear previous results
    findingsList.innerHTML = '';
    driversList.innerHTML = '';
    watchlistList.innerHTML = '';
    customAnswer.innerHTML = '';
    customAnswer.classList.add('hidden');

    if (data.scope === 'custom' && data.customAnswer) {
      // Show custom answer
      customAnswer.textContent = data.customAnswer;
      customAnswer.classList.remove('hidden');

      // Hide structured sections
      findingsList.parentElement.classList.add('hidden');
      driversList.parentElement.classList.add('hidden');
      watchlistList.parentElement.classList.add('hidden');
    } else {
      // Show structured results
      findingsList.parentElement.classList.remove('hidden');
      driversList.parentElement.classList.remove('hidden');
      watchlistList.parentElement.classList.remove('hidden');

      // Key Findings
      if (data.keyFindings && data.keyFindings.length > 0) {
        data.keyFindings.forEach(finding => {
          const li = document.createElement('li');
          li.textContent = finding;
          findingsList.appendChild(li);
        });
      }

      // Drivers
      if (data.drivers && data.drivers.length > 0) {
        data.drivers.forEach(driver => {
          const li = document.createElement('li');
          li.textContent = driver;
          driversList.appendChild(li);
        });
      }

      // Watch List
      if (data.watchList && data.watchList.length > 0) {
        data.watchList.forEach(item => {
          const li = document.createElement('li');
          li.textContent = item;
          watchlistList.appendChild(li);
        });
      }

      // News Signal Section (GDELT)
      this.renderNewsSignal(data.newsSignal);
    }

    // Meta info
    resultModel.textContent = `Model: ${data.model || 'Unknown'}`;
    if (data.generatedAt) {
      const time = new Date(data.generatedAt).toLocaleTimeString();
      resultTime.textContent = `Generated: ${time}`;
    }

    resultsContainer.classList.remove('hidden');
    Haptics.medium();
  },

  renderNewsSignal(newsSignal) {
    // Get or create news signal container
    let newsContainer = document.getElementById('news-signal-container');
    if (!newsContainer) {
      // Create container after watchlist section
      const watchlistSection = document.getElementById('watchlist-list')?.parentElement;
      if (watchlistSection) {
        newsContainer = document.createElement('div');
        newsContainer.id = 'news-signal-container';
        newsContainer.className = 'news-signal-section';
        watchlistSection.after(newsContainer);
      }
    }

    if (!newsContainer) return;

    // Hide if no news signal data
    if (!newsSignal || !newsSignal.headlines || newsSignal.headlines.length === 0) {
      newsContainer.innerHTML = '';
      newsContainer.classList.add('hidden');
      return;
    }

    const levelClass = newsSignal.level === 'CRITICAL' ? 'news-critical' :
                       newsSignal.level === 'HIGH' ? 'news-high' :
                       newsSignal.level === 'MEDIUM' ? 'news-medium' : 'news-low';

    const convergenceBadge = newsSignal.convergenceTag
      ? `<span class="news-convergence-badge ${newsSignal.convergent ? 'convergent' : 'investigate'}">${newsSignal.convergenceIcon || ''} ${newsSignal.convergenceTag}</span>`
      : '';

    const headlinesHtml = newsSignal.headlines.map(h => {
      const link = h.url
        ? `<a href="${h.url}" target="_blank" rel="noopener" class="news-headline-link">${h.title}</a>`
        : `<span class="news-headline-text">${h.title}</span>`;
      const source = h.source ? `<span class="news-source">${h.source}</span>` : '';
      return `<li>${link} ${source}</li>`;
    }).join('');

    // Humanitarian reports (ReliefWeb - official UN/NGO)
    const humanitarianHtml = newsSignal.humanitarianReports && newsSignal.humanitarianReports.length > 0
      ? `<div class="humanitarian-reports-section">
           <div class="humanitarian-label">Humanitarian Intel (ReliefWeb)</div>
           <ul class="humanitarian-reports-list">
             ${newsSignal.humanitarianReports.map(h => {
               const link = h.url
                 ? `<a href="${h.url}" target="_blank" rel="noopener" class="humanitarian-link">${h.title}</a>`
                 : `<span>${h.title}</span>`;
               const source = h.source ? `<span class="humanitarian-source">${h.source}</span>` : '';
               return `<li>${link} ${source}</li>`;
             }).join('')}
           </ul>
         </div>`
      : '';

    const insightHtml = newsSignal.operationalInsight
      ? `<div class="news-insight">
           <span class="news-insight-label">Operational insight:</span>
           <span class="news-insight-text">${newsSignal.operationalInsight}</span>
         </div>`
      : '';

    newsContainer.innerHTML = `
      <div class="news-signal-header">
        <span class="news-signal-title">News Signal</span>
        <span class="news-signal-country">${newsSignal.countryName}</span>
        <span class="news-signal-level ${levelClass}">${newsSignal.level} ${newsSignal.levelIcon || ''}</span>
        ${convergenceBadge}
      </div>
      <div class="news-signal-stat">
        Media spike: ${newsSignal.spikeStat}
      </div>
      <ul class="news-headlines-list">
        ${headlinesHtml}
      </ul>
      ${humanitarianHtml}
      ${insightHtml}
      <div class="news-disclaimer">Media signal ≠ confirmed operational event</div>
    `;

    newsContainer.classList.remove('hidden');
  },

  renderError(message) {
    const resultsContainer = document.getElementById('ai-results');
    const findingsList = document.getElementById('findings-list');

    findingsList.innerHTML = `<li style="color: var(--status-critical);">Error: ${message}</li>`;
    resultsContainer.classList.remove('hidden');
  },

  async loadPriorityWatch() {
    // Load AI analysis to populate priority watch cards
    try {
      const response = await fetch('/api/analysis/global');
      if (!response.ok) return;

      const data = await response.json();
      if (!data.watchList || data.watchList.length < 3) return;

      const container = document.getElementById('priority-watch-cards');
      if (!container) return;

      // Parse watchList items to extract country, reason, action
      // Format: "CRITICAL: Sudan — IPC Phase 5 + ... — high risk of..."
      const priorities = data.watchList.slice(0, 3).map((item, index) => {
        const parts = item.split(/[—→]/);
        // Extract country name, removing prefix like "CRITICAL:", "IMMEDIATE:", "SCALE:"
        let countryPart = parts[0]?.trim() || '';
        const countryMatch = countryPart.match(/^(?:CRITICAL|IMMEDIATE|SCALE|ALERT|WARNING):\s*(.+)$/i);
        const country = countryMatch ? countryMatch[1].trim() : countryPart;

        return {
          country: country || 'Unknown',
          reason: parts[1]?.trim() || '',
          action: parts[2]?.trim() || ''
        };
      });

      const priorityClasses = ['priority-critical', 'priority-high', 'priority-warning'];

      // First item - LARGE card
      const first = priorities[0];
      const largeCard = `
        <div class="priority-card-large ${priorityClasses[0]}">
          <div class="priority-rank-large">1</div>
          <div class="priority-content-large">
            <div class="priority-country-large">${first.country}</div>
            <div class="priority-stats">
              <span class="priority-stat">${first.reason}</span>
            </div>
            <div class="priority-drivers">Drivers: Conflict + Economic collapse + Displacement</div>
            <div class="priority-action-large">${first.action}</div>
          </div>
        </div>
      `;

      // Second and third items - SMALL cards
      const smallCards = priorities.slice(1).map((p, i) => `
        <div class="priority-card-small ${priorityClasses[i + 1]}">
          <div class="priority-rank-small">${i + 2}</div>
          <div class="priority-content-small">
            <div class="priority-country-small">${p.country}</div>
            <div class="priority-reason-small">${p.reason}</div>
          </div>
        </div>
      `).join('');

      container.innerHTML = `
        ${largeCard}
        <div class="priority-small-grid">
          ${smallCards}
        </div>
      `;

    } catch (error) {
      console.log('Priority watch loading silently failed:', error.message);
      // Keep default static content
    }
  }
};

// ============================================
// DEEP ANALYSIS MANAGER - CLAUDE CONTEXTUAL SCORING
// ============================================
const DeepAnalysisManager = {
  isLoading: false,
  currentIso3: null,
  lastAnalysisTime: 0,
  cooldownMs: 10000, // 10 second cooldown for deep analysis (more expensive)

  init() {
    const btn = document.getElementById('deep-analysis-btn');
    if (!btn) return;

    btn.addEventListener('click', () => this.analyze());
  },

  async analyze(iso3 = null) {
    if (this.isLoading) return;

    // Rate limiting
    const now = Date.now();
    const timeSinceLastAnalysis = now - this.lastAnalysisTime;
    if (timeSinceLastAnalysis < this.cooldownMs) {
      const remainingSeconds = Math.ceil((this.cooldownMs - timeSinceLastAnalysis) / 1000);
      Toast.info(`Deep analysis cooldown: ${remainingSeconds}s remaining`);
      return;
    }

    const btn = document.getElementById('deep-analysis-btn');
    const resultsContainer = document.getElementById('deep-analysis-results');

    if (!btn || !resultsContainer) return;

    // Start loading
    this.isLoading = true;
    btn.classList.add('loading');
    btn.disabled = true;
    btn.querySelector('span').textContent = 'Analyzing...';

    // Show results container with loading state
    resultsContainer.classList.remove('hidden');
    resultsContainer.innerHTML = `
      <div class="deep-analysis-loading" style="text-align: center; padding: var(--space-xl);">
        <div class="loading-spinner" style="margin: 0 auto 16px;"></div>
        <div style="color: var(--text-secondary);">Claude is analyzing contextual risk factors...</div>
        <div style="font-size: 0.75rem; color: var(--text-muted); margin-top: 8px;">This may take 5-10 seconds</div>
      </div>
    `;
    resultsContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

    try {
      const url = iso3 ? `/api/analysis/deep?iso3=${iso3}` : '/api/analysis/deep';
      console.log('[DeepAnalysis] Fetching:', url);

      const response = await fetch(url);
      console.log('[DeepAnalysis] Response status:', response.status);

      if (!response.ok) {
        throw new Error(`Server error: ${response.status}`);
      }

      const text = await response.text();
      console.log('[DeepAnalysis] Raw response (first 200 chars):', text.substring(0, 200));

      const data = JSON.parse(text);
      console.log('[DeepAnalysis] Parsed data:', data);
      console.log('[DeepAnalysis] countryName:', data.countryName);
      console.log('[DeepAnalysis] score:', data.score);

      this.currentIso3 = data.iso3;
      this.renderResults(data, resultsContainer);

    } catch (error) {
      console.error('[DeepAnalysis] Error:', error);
      this.renderError(error.message, resultsContainer);
    } finally {
      this.isLoading = false;
      this.lastAnalysisTime = Date.now(); // Rate limiting timestamp
      btn.classList.remove('loading');
      btn.disabled = false;
      btn.querySelector('span').textContent = 'Deep Analysis';
    }
  },

  renderResults(data, container) {
    // Debug logging
    console.log('Deep Analysis Response:', data);

    // Check for error cases
    if (!data) {
      this.renderError('No data received from server', container);
      return;
    }

    if (data.score === 0 && data.reasoning && data.reasoning.includes('not configured')) {
      this.renderError(data.reasoning, container);
      return;
    }

    // Ensure we have basic data
    const countryName = data.countryName || data.iso3 || 'Unknown';
    const score = data.score !== undefined ? data.score : 'N/A';
    const riskLevel = data.riskLevel || 'N/A';
    const levelClass = riskLevel.toLowerCase();
    const trajectoryClass = (data.trajectory || 'stable').toLowerCase();

    // Default weights for comparison
    const defaultWeights = { climate: 25, conflict: 25, economic: 20, food: 30 };

    container.innerHTML = `
      <div class="deep-analysis-header">
        <div class="deep-analysis-title">
          <h4>Deep Contextual Analysis</h4>
          <span class="deep-analysis-country">${Utils.escapeHtml(countryName)}</span>
        </div>
        <div class="deep-analysis-score">
          <span class="deep-score-value ${levelClass}">${score}</span>
          <span class="deep-score-level">${Utils.escapeHtml(riskLevel)}</span>
        </div>
      </div>

      ${data.weights ? `
        <div class="deep-analysis-weights">
          <div class="weight-item">
            <span class="weight-label">Climate</span>
            <span class="weight-value">${data.weights.climate || 0}%</span>
            <span class="weight-default">(default: ${defaultWeights.climate}%)</span>
          </div>
          <div class="weight-item">
            <span class="weight-label">Conflict</span>
            <span class="weight-value">${data.weights.conflict || 0}%</span>
            <span class="weight-default">(default: ${defaultWeights.conflict}%)</span>
          </div>
          <div class="weight-item">
            <span class="weight-label">Economic</span>
            <span class="weight-value">${data.weights.economic || 0}%</span>
            <span class="weight-default">(default: ${defaultWeights.economic}%)</span>
          </div>
          <div class="weight-item">
            <span class="weight-label">Food</span>
            <span class="weight-value">${data.weights.food || 0}%</span>
            <span class="weight-default">(default: ${defaultWeights.food}%)</span>
          </div>
        </div>
        ${data.weightReasoning ? `
          <div class="weight-reasoning">${Utils.escapeHtml(data.weightReasoning)}</div>
        ` : ''}
      ` : ''}

      ${data.reasoning ? `
        <div class="deep-analysis-section">
          <h5>Assessment</h5>
          <p>${Utils.escapeHtml(data.reasoning)}</p>
        </div>
      ` : ''}

      ${data.drivers && data.drivers.length > 0 ? `
        <div class="deep-analysis-section">
          <h5>Key Drivers</h5>
          <ul class="deep-drivers-list">
            ${data.drivers.map(d => `<li>${Utils.escapeHtml(d)}</li>`).join('')}
          </ul>
        </div>
      ` : ''}

      ${data.trajectory ? `
        <div class="deep-analysis-section">
          <h5>30-Day Trajectory</h5>
          <span class="deep-trajectory ${trajectoryClass}">${Utils.escapeHtml(data.trajectory)}</span>
          ${data.trajectoryReason ? `
            <div class="trajectory-reason">${Utils.escapeHtml(data.trajectoryReason)}</div>
          ` : ''}
        </div>
      ` : ''}

      ${data.hotspots && data.hotspots.length > 0 ? `
        <div class="deep-analysis-section">
          <h5>Hotspot Indicators to Watch</h5>
          <div class="deep-hotspots">
            ${data.hotspots.map(h => `<span class="hotspot-tag">${Utils.escapeHtml(h)}</span>`).join('')}
          </div>
        </div>
      ` : ''}

      ${data.confidenceLevel ? `
        <div class="deep-confidence">
          <span class="confidence-badge ${(data.confidenceLevel || '').toLowerCase()}">${Utils.escapeHtml(data.confidenceLevel)}</span>
          <span class="confidence-reason">${Utils.escapeHtml(data.confidenceReason || '')}</span>
        </div>
      ` : ''}

      <div class="deep-analysis-meta">
        <span>Model: ${Utils.escapeHtml(data.model || 'Claude')}</span>
        <span>Generated: ${data.generatedAt ? new Date(data.generatedAt).toLocaleTimeString() : 'just now'} ${data.durationMs ? `(${Math.round(data.durationMs / 1000)}s)` : ''}</span>
      </div>
    `;
  },

  renderError(message, container) {
    container.innerHTML = `
      <div class="deep-analysis-error">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/>
          <path d="M12 8v4M12 16h.01"/>
        </svg>
        <div>${Utils.escapeHtml(message)}</div>
      </div>
    `;
  }
};

// ============================================
// SITUATION DETECTION MANAGER - CLAUDE-NATIVE
// ============================================
const SituationDetectionManager = {
  isLoading: false,

  init() {
    const btn = document.getElementById('detect-situations-btn');
    if (!btn) return;

    btn.addEventListener('click', () => this.detect());

    // Hide detection results panel completely - main list shows the data
    const resultsContainer = document.getElementById('detect-situations-results');
    if (resultsContainer) {
      resultsContainer.style.display = 'none';
    }
  },

  async detect() {
    if (this.isLoading) return;

    const btn = document.getElementById('detect-situations-btn');
    if (!btn) return;

    // Start loading - button only, no separate panel
    this.isLoading = true;
    btn.classList.add('loading');
    btn.disabled = true;
    btn.querySelector('span').textContent = 'Analyzing...';

    // Show loading in the situations list
    const situationsList = document.getElementById('situations-list');
    if (situationsList) {
      situationsList.innerHTML = `
        <div style="text-align: center; padding: var(--space-xl);">
          <div class="loading-spinner" style="margin: 0 auto 16px;"></div>
          <div style="color: var(--text-secondary);">Claude is analyzing triggered countries...</div>
        </div>
      `;
    }

    try {
      // Add cache-busting parameter to ensure fresh detection
      const response = await fetch(`/api/situations/detect?_=${Date.now()}`, {
        cache: 'no-store'
      });
      console.log('[SituationDetection] Response status:', response.status);

      if (!response.ok) {
        throw new Error(`Server error: ${response.status}`);
      }

      const data = await response.json();
      console.log('[SituationDetection] Detection result:', data.status, data.situations?.length, 'situations');

      // Always refresh the main situations list
      SituationManager.loaded = false;
      SituationManager.data = null;
      await SituationManager.init();

    } catch (error) {
      console.error('[SituationDetection] Error:', error);
      if (situationsList) {
        situationsList.innerHTML = `<div style="padding: var(--space-md); color: var(--text-muted);">Detection failed: ${error.message}</div>`;
      }
    } finally {
      this.isLoading = false;
      btn.classList.remove('loading');
      btn.disabled = false;
      btn.querySelector('span').textContent = 'Detect Situations (Claude)';
    }
  },

  renderResults(data, container) {
    // Check for error cases
    if (!data || data.status === 'ERROR') {
      this.renderError(data?.message || 'Unknown error', container);
      return;
    }

    const situations = data.situations || [];
    const analyzed = data.analyzedCountries || 0;
    const duration = data.durationMs ? Math.round(data.durationMs / 1000) : '?';

    if (situations.length === 0) {
      container.innerHTML = `
        <div class="detect-situations-header">
          <h4>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/>
            </svg>
            Claude Situation Detection
          </h4>
          <span class="detect-situations-meta">${analyzed} countries analyzed in ${duration}s</span>
        </div>
        <div class="detect-empty-state">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="12" cy="12" r="10"/>
            <path d="M9 12l2 2 4-4"/>
          </svg>
          <div style="margin-top: var(--space-sm);">No active situations detected</div>
          <div style="font-size: 0.75rem; color: var(--text-muted); margin-top: 4px;">
            No triggered countries found with sufficient evidence
          </div>
        </div>
      `;
      return;
    }

    let html = `
      <div class="detect-situations-header">
        <h4>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"/>
          </svg>
          Claude Situation Detection
        </h4>
        <span class="detect-situations-meta">${situations.length} situations from ${analyzed} countries (${duration}s)</span>
      </div>
    `;

    // Global context if present
    if (data.globalContext) {
      html += `<div class="detect-global-context">${Utils.escapeHtml(data.globalContext)}</div>`;
    }

    html += '<div class="detected-situations-grid">';

    for (const s of situations) {
      const severityClass = (s.severity || 'watch').toLowerCase();
      const trajectoryClass = (s.trajectory || 'unclear').toLowerCase();

      html += `
        <div class="detected-situation-card severity-${severityClass}">
          <div class="detected-situation-header">
            <span class="detected-situation-country">${Utils.escapeHtml(s.countryName || s.iso3)}</span>
            <div class="detected-situation-badges">
              <span class="detected-type-badge">${Utils.escapeHtml(s.type)}</span>
              <span class="detected-severity-badge ${severityClass}">${Utils.escapeHtml(s.severity)}</span>
            </div>
          </div>
          <div class="detected-situation-summary">${Utils.escapeHtml(s.summary)}</div>
          ${s.evidence && s.evidence.length > 0 ? `
            <div class="detected-situation-evidence">
              <ul class="detected-evidence-list">
                ${s.evidence.map(e => `<li>${Utils.escapeHtml(e)}</li>`).join('')}
              </ul>
            </div>
          ` : ''}
          <div class="detected-situation-footer">
            <span class="detected-trajectory ${trajectoryClass}">
              ${trajectoryClass === 'worsening' ? '↗' : trajectoryClass === 'stable' ? '→' : '?'} ${Utils.escapeHtml(s.trajectory)}
            </span>
            <span class="detected-confidence">Confidence: ${Utils.escapeHtml(s.confidence)}</span>
          </div>
        </div>
      `;
    }

    html += '</div>';

    // Meta info
    html += `
      <div class="deep-analysis-meta" style="margin-top: var(--space-lg); padding-top: var(--space-sm); border-top: 1px solid var(--border-subtle);">
        <span>Model: ${Utils.escapeHtml(data.model || 'Claude')}</span>
        <span>Generated: ${data.generatedAt ? new Date(data.generatedAt).toLocaleTimeString() : 'just now'}</span>
      </div>
    `;

    container.innerHTML = html;
  },

  renderError(message, container) {
    container.innerHTML = `
      <div class="detect-situations-error">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/>
          <path d="M12 8v4M12 16h.01"/>
        </svg>
        <div style="margin-top: var(--space-sm);">${Utils.escapeHtml(message)}</div>
      </div>
    `;
  }
};

// ============================================
// COUNTRY DETAIL MODAL
// ============================================
const CountryDetailManager = {
  modal: null,
  currentIso3: null,

  init() {
    this.modal = document.getElementById('country-modal');
    if (!this.modal) return;

    // Close button
    const closeBtn = document.getElementById('country-modal-close');
    if (closeBtn) {
      closeBtn.addEventListener('click', () => this.close());
    }

    // Backdrop click
    const backdrop = this.modal.querySelector('.ai-modal-backdrop');
    if (backdrop) {
      backdrop.addEventListener('click', () => this.close());
    }

    // ESC key
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && !this.modal.classList.contains('hidden')) {
        this.close();
      }
    });

    // Setup click handlers for risk cards
    this.setupRiskCardClicks();
  },

  setupRiskCardClicks() {
    document.querySelectorAll('.risk-card[data-iso3]').forEach(card => {
      card.addEventListener('click', () => {
        const iso3 = card.dataset.iso3;
        const countryName = card.dataset.country;
        if (iso3) {
          this.open(iso3, countryName);
        }
      });
    });
  },

  async open(iso3, countryName) {
    this.currentIso3 = iso3;
    this.modal.classList.remove('hidden');
    document.body.style.overflow = 'hidden';

    const title = document.getElementById('country-modal-title');
    const body = document.getElementById('country-modal-body');

    title.textContent = countryName || iso3;
    body.innerHTML = `
      <div style="text-align: center; padding: 40px;">
        <div class="loading-spinner"></div>
        <div style="margin-top: 12px; color: var(--text-secondary);">Loading country profile...</div>
      </div>
    `;

    try {
      const res = await fetch(`/api/countries/${encodeURIComponent(iso3)}/profile`);
      const profile = res.ok ? await res.json() : null;
      this.render(body, iso3, profile);
    } catch (error) {
      console.error('Error loading country profile:', error);
      body.innerHTML = `
        <div style="text-align: center; padding: 40px; color: var(--text-tertiary);">
          Unable to load country data.
          <br><br>
          <button onclick="CountryDetailManager.open('${Utils.escapeHtml(iso3)}', '${Utils.escapeHtml(countryName || '')}')" class="btn-secondary">Retry</button>
        </div>
      `;
    }
  },

  // Helper: format large numbers
  _fmtNum(n) {
    if (n == null) return null;
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(0) + 'K';
    return n.toLocaleString();
  },

  // Helper: render SVG sparkline from trend data
  _renderSparkline(points) {
    const w = 280, h = 44, pad = 2;
    const scores = points.map(p => p.score);
    const min = Math.max(0, Math.min(...scores) - 5);
    const max = Math.min(100, Math.max(...scores) + 5);
    const range = max - min || 1;

    const coords = scores.map((s, i) => {
      const x = pad + (i / (scores.length - 1)) * (w - 2 * pad);
      const y = pad + (1 - (s - min) / range) * (h - 2 * pad);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });

    const last = scores[scores.length - 1];
    const first = scores[0];
    const color = last > first + 3 ? 'var(--status-critical)' : last < first - 3 ? 'var(--status-low)' : 'var(--text-tertiary)';
    const label = points[0].date + ' → ' + points[points.length - 1].date;

    return `<div class="cp-sparkline" title="${label}">
      <svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">
        <polyline points="${coords.join(' ')}" fill="none" stroke="${color}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        <circle cx="${coords[coords.length-1].split(',')[0]}" cy="${coords[coords.length-1].split(',')[1]}" r="2.5" fill="${color}"/>
      </svg>
      <span class="cp-sparkline-range">${min}-${max}</span>
    </div>`;
  },

  // Helper: risk level CSS class
  _riskClass(level) {
    if (!level) return '';
    const l = level.toLowerCase();
    if (l === 'critical') return 'critical';
    if (l === 'alert') return 'high';
    if (l === 'warning') return 'medium';
    return 'low';
  },

  render(container, iso3, profile) {
    const p = profile || {};
    let html = `<div class="country-detail-content">`;

    // === HEADER: Risk badge + region + trend ===
    const hasScore = p.score != null;
    html += `<div class="cp-header">`;
    if (hasScore) {
      html += `
        <div class="cp-score-badge ${this._riskClass(p.riskLevel)}">
          <span class="cp-score-num">${p.score}</span>
          <span class="cp-score-label">${Utils.escapeHtml(p.riskLevel || '')}</span>
        </div>`;
    }
    html += `<div class="cp-header-info">`;
    if (p.region) html += `<span class="cp-region">${Utils.escapeHtml(p.region)}</span>`;
    if (p.trend) {
      const trendCls = p.trend === 'rising' ? 'trend-up' : p.trend === 'falling' ? 'trend-down' : 'trend-stable';
      html += `<span class="cp-trend ${trendCls}">${Utils.escapeHtml(p.trendIcon || '')} ${Utils.escapeHtml(p.trend)}${p.scoreDelta != null ? ` (${p.scoreDelta > 0 ? '+' : ''}${p.scoreDelta})` : ''}</span>`;
    }
    if (p.persistenceLabel) {
      html += `<span class="cp-persistence">${Utils.escapeHtml(p.persistenceLabel)}${p.persistenceDays ? ` (${p.persistenceDays}d)` : ''}</span>`;
    }
    html += `</div>`;
    if (p.confidence != null) {
      html += `<span class="cp-confidence" title="${Utils.escapeHtml(p.confidenceNote || '')}">Confidence: ${(p.confidence * 100).toFixed(0)}%</span>`;
    }
    html += `</div>`;

    // === TREND SPARKLINE ===
    if (p.trendHistory && p.trendHistory.length >= 2) {
      html += this._renderSparkline(p.trendHistory);
    }

    // === SCORE BREAKDOWN: 4-driver bars ===
    if (hasScore) {
      const drivers = [
        { label: 'Food Security', score: p.foodSecurityScore, weight: '30%', color: 'var(--status-critical)' },
        { label: 'Climate', score: p.climateScore, weight: '25%', color: '#64d2ff' },
        { label: 'Conflict', score: p.conflictScore, weight: '25%', color: 'var(--status-high)' },
        { label: 'Economic', score: p.economicScore, weight: '20%', color: 'var(--accent-purple)' }
      ];
      html += `<div class="country-section">
        <h4>Risk Score Breakdown</h4>
        <div class="cp-drivers">
          ${drivers.map(d => `
            <div class="cp-driver-row">
              <span class="cp-driver-label">${d.label} <span class="cp-driver-weight">${d.weight}</span></span>
              <div class="cp-driver-bar-bg">
                <div class="cp-driver-bar" style="width: ${d.score || 0}%; background: ${d.color};"></div>
              </div>
              <span class="cp-driver-score">${d.score != null ? d.score : '-'}</span>
            </div>
          `).join('')}
        </div>
        ${p.drivers && p.drivers.length ? `<div class="cp-driver-tags">${p.drivers.map(d => `<span class="cp-driver-tag">${Utils.escapeHtml(d)}</span>`).join('')}</div>` : ''}
      </div>`;
    }

    // === FOOD SECURITY ===
    const hasFood = p.ipcPhase || p.peoplePhase3to5 || p.fcsPrevalence;
    if (hasFood) {
      html += `<div class="country-section">
        <h4>Food Security</h4>
        <div class="country-metrics">`;
      if (p.ipcPhase) {
        const phaseInt = Math.round(p.ipcPhase);
        html += `<div class="metric"><span class="metric-label">IPC Phase</span><span class="metric-value phase-${phaseInt}">${phaseInt} - ${Utils.escapeHtml(p.ipcDescription || '')}</span></div>`;
      }
      if (p.peoplePhase3to5) {
        html += `<div class="metric"><span class="metric-label">People IPC 3-5</span><span class="metric-value">${this._fmtNum(p.peoplePhase3to5)}${p.percentPhase3to5 ? ` (${p.percentPhase3to5.toFixed(0)}%)` : ''}</span></div>`;
      }
      if (p.peoplePhase4to5) {
        html += `<div class="metric"><span class="metric-label">People IPC 4-5</span><span class="metric-value" style="color:var(--status-critical)">${this._fmtNum(p.peoplePhase4to5)}${p.percentPhase4to5 ? ` (${p.percentPhase4to5.toFixed(0)}%)` : ''}</span></div>`;
      }
      if (p.fcsPrevalence) {
        html += `<div class="metric"><span class="metric-label">Insufficient food (FCS)</span><span class="metric-value">${p.fcsPrevalence.toFixed(0)}%${p.fcsPeople ? ` (${this._fmtNum(p.fcsPeople)})` : ''}</span></div>`;
      }
      if (p.rcsiPrevalence) {
        html += `<div class="metric"><span class="metric-label">Crisis coping (rCSI)</span><span class="metric-value">${p.rcsiPrevalence.toFixed(0)}%${p.rcsiPeople ? ` (${this._fmtNum(p.rcsiPeople)})` : ''}</span></div>`;
      }
      html += `</div></div>`;
    }

    // === CLIMATE ===
    if (p.precipAnomaly != null) {
      const catClass = p.precipCategory === 'DROUGHT' ? 'drought' : p.precipCategory === 'FLOODING_RISK' ? 'flood' : 'normal';
      html += `<div class="country-section">
        <h4>Climate</h4>
        <div class="country-metrics">
          <div class="metric"><span class="metric-label">Precipitation Anomaly</span><span class="metric-value ${catClass}">${p.precipAnomaly > 0 ? '+' : ''}${p.precipAnomaly.toFixed(0)}%</span></div>
          <div class="metric"><span class="metric-label">Category</span><span class="metric-value ${catClass}">${Utils.escapeHtml((p.precipCategory || '').replace(/_/g, ' '))}</span></div>
        </div>
      </div>`;
    }

    // === CONFLICT ===
    if (p.gdeltZScore != null) {
      const spikeClass = p.spikeLevel === 'CRITICAL' ? 'critical' : p.spikeLevel === 'HIGH' ? 'high' : p.spikeLevel === 'ELEVATED' ? 'medium' : '';
      html += `<div class="country-section">
        <h4>Conflict / Media</h4>
        <div class="country-metrics">
          <div class="metric"><span class="metric-label">Media Intensity (z-score)</span><span class="metric-value ${spikeClass}">${p.gdeltZScore.toFixed(1)}</span></div>
          <div class="metric"><span class="metric-label">Spike Level</span><span class="metric-value ${spikeClass}">${Utils.escapeHtml(p.spikeLevel || 'NORMAL')}</span></div>
          ${p.articles7d != null ? `<div class="metric"><span class="metric-label">Articles (7d)</span><span class="metric-value">${p.articles7d.toLocaleString()}</span></div>` : ''}
        </div>`;
      if (p.headlines && p.headlines.length) {
        html += `<div class="cp-headlines">
          ${p.headlines.map(h => `<div class="cp-headline">${Utils.escapeHtml(h.title)}</div>`).join('')}
        </div>`;
      }
      html += `</div>`;
    }

    // === ECONOMY ===
    const hasEcon = p.currencyChange30d != null || p.inflationRate != null;
    if (hasEcon) {
      html += `<div class="country-section">
        <h4>Economy</h4>
        <div class="country-metrics">`;
      if (p.currencyChange30d != null) {
        const curClass = p.currencyChange30d < -10 ? 'critical' : p.currencyChange30d < -5 ? 'high' : '';
        html += `<div class="metric"><span class="metric-label">${Utils.escapeHtml(p.currencyCode || 'Currency')} (30d)</span><span class="metric-value ${curClass}">${p.currencyChange30d > 0 ? '+' : ''}${p.currencyChange30d.toFixed(1)}%</span></div>`;
      }
      if (p.inflationRate != null) {
        const infClass = p.inflationRate >= 25 ? 'critical' : p.inflationRate >= 15 ? 'high' : '';
        html += `<div class="metric"><span class="metric-label">Inflation${p.inflationYear ? ` (${p.inflationYear})` : ''}</span><span class="metric-value ${infClass}">${p.inflationRate.toFixed(1)}%</span></div>`;
      }
      html += `</div></div>`;
    }

    // === DISPLACEMENT ===
    const hasDisp = p.idps || p.refugees;
    if (hasDisp) {
      html += `<div class="country-section">
        <h4>Displacement</h4>
        <div class="country-metrics">
          ${p.idps ? `<div class="metric"><span class="metric-label">IDPs (IOM DTM)</span><span class="metric-value">${this._fmtNum(p.idps)}</span></div>` : ''}
          ${p.refugees ? `<div class="metric"><span class="metric-label">Refugees (UNHCR)</span><span class="metric-value">${this._fmtNum(p.refugees)}</span></div>` : ''}
        </div>
      </div>`;
    }

    // === HORIZON (forward-looking) ===
    if (p.horizon) {
      html += `<div class="country-section cp-horizon">
        <h4>Outlook</h4>
        <div class="cp-horizon-text">${Utils.escapeHtml(p.horizon)}</div>
        ${p.horizonReason ? `<div class="cp-horizon-reason">${Utils.escapeHtml(p.horizonReason)}</div>` : ''}
      </div>`;
    }

    // === RECENT REPORTS ===
    if (p.recentReports && p.recentReports.length) {
      html += `<div class="country-section">
        <h4>Recent Reports</h4>
        <div class="cp-reports">
          ${p.recentReports.map(r => `
            <a class="cp-report" href="${Utils.sanitizeUrl(r.url)}" target="_blank" rel="noopener noreferrer">
              <span class="cp-report-title">${Utils.escapeHtml(r.title)}</span>
              <span class="cp-report-meta">${Utils.escapeHtml(r.source || '')}${r.date ? ` - ${r.date}` : ''}${r.format ? ` [${r.format}]` : ''}</span>
            </a>
          `).join('')}
        </div>
      </div>`;
    }

    // === WHO DISEASE OUTBREAKS ===
    if (p.diseaseOutbreaks && p.diseaseOutbreaks.length) {
      html += `<div class="country-section">
        <h4>WHO Disease Outbreaks</h4>
        <div class="cp-outbreaks">
          ${p.diseaseOutbreaks.map(o => `
            <a class="cp-outbreak" href="${Utils.sanitizeUrl(o.url)}" target="_blank" rel="noopener noreferrer">
              <span class="cp-outbreak-disease">${Utils.escapeHtml(o.disease || '')}</span>
              <span class="cp-outbreak-meta">${o.timeAgo || o.date || ''}</span>
            </a>
          `).join('')}
        </div>
      </div>`;
    }

    // === DATA FRESHNESS FOOTER ===
    if (p.dataFreshness) {
      const entries = Object.entries(p.dataFreshness);
      if (entries.length > 0) {
        html += `<div class="cp-freshness">
          ${entries.map(([name, status]) => {
            const cls = status === 'fresh' ? 'fresh' : status === 'recent' ? 'recent' : status === 'stale' ? 'stale' : 'unavail';
            return `<span class="cp-freshness-dot ${cls}" title="${Utils.escapeHtml(name)}: ${status}"></span>`;
          }).join('')}
          <span class="cp-freshness-label">${entries.filter(([,s]) => s === 'fresh').length}/${entries.length} sources fresh</span>
        </div>`;
      }
    }

    // === ACTION BUTTON ===
    html += `
      <div class="country-actions">
        <button class="btn-primary" onclick="AIAnalysisManager.analyzeCountry('${Utils.escapeHtml(iso3)}'); CountryDetailManager.close();">
          Deep Analysis with Claude
        </button>
      </div>
    `;

    html += `</div>`;
    container.innerHTML = html;
  },

  close() {
    if (this.modal) {
      this.modal.classList.add('hidden');
      document.body.style.overflow = '';
    }
    this.currentIso3 = null;
  }
};

// ============================================
// SIDEBAR NAVIGATION
// ============================================
const SidebarManager = {
  currentSection: 'overview',
  sectionDataLoaded: new Set(),

  init() {
    const sidebarItems = document.querySelectorAll('.sidebar-item');
    const sections = document.querySelectorAll('.content-section');
    const sidebarToggle = document.getElementById('sidebar-toggle');
    const sidebar = document.getElementById('sidebar');

    if (sidebarItems.length === 0) return;

    // Sidebar navigation
    sidebarItems.forEach(item => {
      item.addEventListener('click', () => {
        const sectionId = item.dataset.section;
        this.switchSection(sectionId);
        Haptics.light();

        // Close sidebar on mobile
        if (window.innerWidth <= 1024) {
          sidebar?.classList.remove('open');
          document.body.classList.remove('sidebar-open');
        }
      });
    });

    // Mobile sidebar toggle
    if (sidebarToggle && sidebar) {
      sidebarToggle.addEventListener('click', () => {
        sidebar.classList.toggle('open');
        document.body.classList.toggle('sidebar-open');
        Haptics.light();
      });

      // Close sidebar when clicking outside on mobile
      document.addEventListener('click', (e) => {
        if (window.innerWidth <= 1024 &&
            sidebar.classList.contains('open') &&
            !sidebar.contains(e.target) &&
            !sidebarToggle.contains(e.target)) {
          sidebar.classList.remove('open');
          document.body.classList.remove('sidebar-open');
        }
      });
    }

    // Initialize first section
    this.loadSectionData('overview');
  },

  switchSection(sectionId) {
    const sidebarItems = document.querySelectorAll('.sidebar-item');
    const sections = document.querySelectorAll('.content-section');

    // Update sidebar active state with accessibility
    sidebarItems.forEach(item => {
      const isActive = item.dataset.section === sectionId;
      item.classList.toggle('active', isActive);
      item.setAttribute('aria-pressed', isActive);
      item.setAttribute('aria-current', isActive ? 'page' : 'false');
    });

    // Update section visibility
    sections.forEach(section => {
      section.classList.toggle('active', section.dataset.section === sectionId);
    });

    this.currentSection = sectionId;
    this.loadSectionData(sectionId);

    // Focus management: move focus to section heading for screen readers
    const activeSection = document.querySelector(`.content-section[data-section="${sectionId}"]`);
    const heading = activeSection?.querySelector('h2, h3');
    if (heading) {
      heading.setAttribute('tabindex', '-1');
      heading.focus();
    }

    // Fix Leaflet map rendering when Countries section becomes visible
    if (sectionId === 'countries' && window.CrisisMap) {
      setTimeout(() => {
        window.CrisisMap.init();
      }, 100);
    }

    // Scroll to top of main content
    document.querySelector('.main-content')?.scrollTo(0, 0);
  },

  loadSectionData(sectionId) {
    // Load data only once per section
    if (this.sectionDataLoaded.has(sectionId)) return;

    switch (sectionId) {
      case 'overview':
        OverviewManager.init();
        break;
      case 'countries':
        // Initialize or invalidate map when section becomes visible
        if (window.CrisisMap) {
          setTimeout(() => {
            window.CrisisMap.init(); // Will init or invalidate
          }, 100);
        }
        break;
      case 'early-warning':
        RiskScoreMonitor.init();
        IPCMonitor.init();
        ClusterAlertMonitor.loadFull();
        IntelligenceManager.init(); // Top Risk Spotlight
        // Load Current Focus section (moved from Overview)
        OverviewManager.loadFocusNow();
        OverviewManager.loadBriefBullets();
        break;
      case 'news-feed':
        NewsFeedManager.init();
        break;
      case 'drivers':
        // Driver tabs handle their own loading
        DriverTabManager.init();
        break;
      case 'situational':
        // Operations Mode: Active Situations only
        SituationManager.init();
        break;
      case 'intelligence':
        // Analysis Mode: Topic Reports, Daily Briefing
        TopicReportGenerator.init();
        DailyBriefingManager.init();
        break;
    }

    this.sectionDataLoaded.add(sectionId);
  },

  async loadGlobalStatus() {
    try {
      // Load risk scores
      const response = await fetch('/api/risk/scores');
      const result = await response.json();
      const scores = result.data || result;

      if (scores && scores.length > 0) {
        const criticalCount = scores.filter(s => s.level === 'CRITICAL').length;
        const alertCount = scores.filter(s => s.level === 'ALERT').length;
        const topCritical = scores.filter(s => s.level === 'CRITICAL').slice(0, 3);

        // Update critical count in banner
        const criticalCountEl = document.getElementById('critical-count');
        if (criticalCountEl) {
          criticalCountEl.textContent = `${criticalCount} countries CRITICAL`;
        }

        // Update regions
        const regionsEl = document.getElementById('status-regions');
        if (regionsEl && topCritical.length > 0) {
          const regions = this.getRegions(topCritical);
          regionsEl.textContent = regions.join(' · ');
        }

        // Update status badge based on severity
        const statusBadge = document.getElementById('status-badge');
        if (statusBadge) {
          const statusText = statusBadge.querySelector('.status-text');
          if (criticalCount >= 3) {
            statusBadge.classList.remove('status-stable', 'status-improving');
            statusBadge.classList.add('status-deteriorating');
            if (statusText) statusText.textContent = 'DETERIORATING';
          } else if (criticalCount >= 1 || alertCount >= 3) {
            statusBadge.classList.remove('status-stable', 'status-improving');
            statusBadge.classList.add('status-deteriorating');
            if (statusText) statusText.textContent = 'ELEVATED';
          }
        }
      }

      // Load countries for Priority Watch context
      const countriesResponse = await fetch('/api/countries');
      const countries = await countriesResponse.json();
      if (countries && countries.length >= 3) {
        const top3People = countries.slice(0, 3).reduce((sum, c) => sum + (c.peoplePhase3to5 || 0), 0);
        const priorityContext = document.getElementById('priority-context');
        if (priorityContext && top3People > 0) {
          const millions = (top3People / 1000000).toFixed(1);
          priorityContext.textContent = `Top 3 = ${millions}M people IPC 3+`;
        }
      }

      // Load executive summary from AI Brief
      this.loadExecutiveSummary();

    } catch (error) {
      console.log('Global status update failed silently');
    }
  },

  getRegions(countries) {
    const regionMap = {
      // Horn of Africa / East Africa
      'SDN': 'Horn of Africa', 'SSD': 'Horn of Africa', 'SOM': 'Horn of Africa', 'ETH': 'Horn of Africa',
      'KEN': 'East Africa', 'UGA': 'East Africa',
      // Central Africa
      'COD': 'Central Africa', 'CAF': 'Central Africa', 'TCD': 'Central Africa',
      'CMR': 'Central Africa', 'RWA': 'Central Africa', 'BDI': 'Central Africa',
      // West Africa / Sahel
      'NGA': 'West Africa', 'MLI': 'Sahel', 'NER': 'Sahel', 'BFA': 'Sahel',
      // Southern Africa
      'MOZ': 'Southern Africa',
      // MENA / Middle East
      'SYR': 'Middle East', 'IRQ': 'Middle East', 'YEM': 'Middle East', 'LBN': 'Middle East',
      'PSE': 'Middle East', 'LBY': 'North Africa',
      // Asia
      'AFG': 'South Asia', 'PAK': 'South Asia', 'BGD': 'South Asia', 'MMR': 'Southeast Asia',
      // LAC
      'HTI': 'Caribbean', 'VEN': 'South America', 'COL': 'South America',
      // Europe
      'UKR': 'Europe'
    };
    const regions = [...new Set(countries.map(c => regionMap[c.iso3] || 'Other'))];
    return regions.slice(0, 3);
  },

  async loadExecutiveSummary() {
    try {
      const response = await fetch('/api/analysis/global');
      const data = await response.json();

      // Update executive summary
      const execText = document.getElementById('exec-summary-text');
      if (execText && data.keyFindings && data.keyFindings.length > 0) {
        execText.textContent = data.keyFindings[0];
      }

      // Update "Why this matters" / Next 30 days
      const todayText = document.getElementById('today-text');
      if (todayText && data.keyFindings && data.keyFindings.length > 1) {
        todayText.textContent = data.keyFindings[1];
      }

      // Update Priority Watch cards if watchList exists
      if (data.watchList && data.watchList.length > 0) {
        // Parse watchList strings into objects
        // Format: "CRITICAL: Sudan — IPC Phase 5 + ... — high risk of..."
        const parsedWatchList = data.watchList.map(item => {
          const parts = item.split(/[—→]/);
          let countryPart = parts[0]?.trim() || '';
          const countryMatch = countryPart.match(/^(?:CRITICAL|IMMEDIATE|SCALE|ALERT|WARNING):\s*(.+)$/i);
          const country = countryMatch ? countryMatch[1].trim() : countryPart;

          return {
            country: country || 'Unknown',
            reason: parts[1]?.trim() || '',
            action: parts[2]?.trim() || ''
          };
        });
        this.updatePriorityCards(parsedWatchList);
      }

    } catch (error) {
      console.log('Executive summary load failed silently');
    }
  },

  updatePriorityCards(watchList) {
    const container = document.getElementById('priority-watch-cards');
    if (!container || watchList.length === 0) return;

    const items = watchList.slice(0, 3);
    const priorityClasses = ['priority-critical', 'priority-high', 'priority-warning'];

    // First item - LARGE card
    const first = items[0];
    const drivers = first.drivers || 'Conflict + Economic collapse + Displacement';
    const largeCard = `
      <div class="priority-card-large ${priorityClasses[0]}">
        <div class="priority-rank-large">1</div>
        <div class="priority-content-large">
          <div class="priority-country-large">${first.country}</div>
          <div class="priority-stats">
            <span class="priority-stat">${first.reason || ''}</span>
          </div>
          <div class="priority-drivers">Drivers: ${drivers}</div>
          <div class="priority-action-large">${first.action || ''}</div>
        </div>
      </div>
    `;

    // Second and third items - SMALL cards
    const smallCards = items.slice(1).map((item, i) => `
      <div class="priority-card-small ${priorityClasses[i + 1] || 'priority-warning'}">
        <div class="priority-rank-small">${i + 2}</div>
        <div class="priority-content-small">
          <div class="priority-country-small">${item.country}</div>
          <div class="priority-reason-small">${item.reason || ''}</div>
        </div>
      </div>
    `).join('');

    container.innerHTML = `
      ${largeCard}
      <div class="priority-small-grid">
        ${smallCards}
      </div>
    `;
  }
};

// ============================================
// DRIVER TAB MANAGER (for Drivers section)
// ============================================
const DriverTabManager = {
  initialized: false,

  init() {
    if (this.initialized) return;

    const tabBtns = document.querySelectorAll('[data-tab^="driver-"]');
    const tabPanes = document.querySelectorAll('[id^="tab-driver-"]');

    if (tabBtns.length === 0) return;

    tabBtns.forEach(btn => {
      btn.addEventListener('click', () => {
        const tabId = btn.dataset.tab;

        // Update button states
        tabBtns.forEach(b => b.classList.remove('active'));
        btn.classList.add('active');

        // Update pane states
        tabPanes.forEach(pane => {
          pane.classList.remove('active');
          if (pane.id === `tab-${tabId}`) {
            pane.classList.add('active');
          }
        });

        // Load tab data
        this.loadTabData(tabId);
        Haptics.light();
      });
    });

    // Load first tab
    this.loadTabData('driver-climate');
    this.initialized = true;
  },

  loadTabData(tabId) {
    switch (tabId) {
      case 'driver-climate':
        ClimateMonitor.init();
        break;
      case 'driver-conflict':
        ConflictMonitor.init();
        break;
      case 'driver-economy':
        CurrencyMonitor.init();
        break;
      case 'driver-displacement':
        // Already rendered server-side
        break;
    }
  }
};

// ============================================
// INTELLIGENCE MANAGER (Centralized Feed)
// ============================================
const IntelligenceManager = {
  loaded: false,
  feedData: null,
  retryCount: 0,
  maxRetries: 2,

  async init() {
    if (this.loaded && this.feedData) {
      // Already loaded, just render
      this.renderAll(this.feedData);
      return;
    }

    // Show loading states
    this.showLoading();

    // Single API call for all intelligence data
    try {
      const response = await fetch('/api/intelligence/feed');

      if (!response.ok) {
        console.error('Intelligence feed response not ok:', response.status);
        this.showError(`API error: ${response.status}`);
        return;
      }

      const data = await response.json();
      console.log('Intelligence feed data:', data);

      if (data && data.status === 'WARMING_UP') {
        // Server is warming up - show friendly message, single retry after 30s
        console.log('Intelligence cache warming up...');
        this.showWarmingUp();
        if (this.retryCount < this.maxRetries) {
          this.retryCount++;
          setTimeout(() => { this.init(); }, 30000); // Single retry after 30s
        }
        return;
      }

      if (data && (data.status === 'READY' || data.topRiskCountry)) {
        this.feedData = data;
        this.renderAll(data);
        this.loaded = true;
        this.retryCount = 0;
      } else {
        console.warn('Intelligence data incomplete:', data);
        this.showWarmingUp();
      }
    } catch (error) {
      console.error('Failed to load intelligence feed:', error);
      console.error('Error stack:', error.stack);
      this.showError('Unable to load intelligence data');
    }
  },

  showLoading() {
    const signalContainer = document.getElementById('intelligence-signal-content');
    const reportsContainer = document.getElementById('reliefweb-reports-list');
    const gdeltContainer = document.getElementById('gdelt-media-container');

    if (signalContainer) Utils.showSkeleton(signalContainer, 'list', 3);
    if (reportsContainer) Utils.showSkeleton(reportsContainer, 'news', 3);
    if (gdeltContainer) Utils.showSkeleton(gdeltContainer, 'card', 2);
  },

  showError(message) {
    const errorHtml = `<div class="text-muted" style="padding: var(--space-md);">${message}</div>`;

    const signalContainer = document.getElementById('intelligence-signal-content');
    const reportsContainer = document.getElementById('reliefweb-reports-list');
    const gdeltContainer = document.getElementById('gdelt-media-container');

    if (signalContainer) signalContainer.innerHTML = errorHtml;
    if (reportsContainer) reportsContainer.innerHTML = errorHtml;
    if (gdeltContainer) gdeltContainer.innerHTML = errorHtml;
  },

  showWarmingUp() {
    const warmingHtml = `
      <div style="padding: var(--space-md); text-align: center;">
        <div class="text-muted">Preparing intelligence data...</div>
        <div class="text-muted" style="font-size: 0.85em; margin-top: 4px;">Data will appear shortly</div>
      </div>
    `;

    const signalContainer = document.getElementById('intelligence-signal-content');
    const reportsContainer = document.getElementById('reliefweb-reports-list');
    const gdeltContainer = document.getElementById('gdelt-media-container');

    if (signalContainer) signalContainer.innerHTML = warmingHtml;
    if (reportsContainer) reportsContainer.innerHTML = warmingHtml;
    if (gdeltContainer) gdeltContainer.innerHTML = warmingHtml;
  },

  renderAll(data) {
    this.renderNewsSignal(data);
    this.renderReliefWebReports(data);
    this.renderGDELTCoverage(data);
    this.loadWHOOutbreaks();
  },

  async loadWHOOutbreaks() {
    const container = document.getElementById('who-outbreaks-container');
    if (!container) return;
    try {
      const resp = await fetch('/api/who/outbreaks');
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const outbreaks = await resp.json();
      if (!outbreaks || outbreaks.length === 0) {
        container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">No recent outbreaks</div>';
        return;
      }
      container.innerHTML = `
        <div class="who-outbreak-list">
          ${outbreaks.slice(0, 10).map(o => `
            <div class="who-outbreak-item">
              <div class="who-outbreak-top">
                <span class="who-outbreak-disease">${Utils.escapeHtml(o.disease || o.title)}</span>
                ${o.country ? `<span class="who-outbreak-country">${Utils.escapeHtml(o.country)}</span>` : ''}
              </div>
              <div class="who-outbreak-bottom">
                <span class="who-outbreak-date">${o.timeAgo || o.publishedDate || ''}</span>
                ${o.url ? `<a href="${Utils.sanitizeUrl(o.url)}" target="_blank" rel="noopener noreferrer" class="who-outbreak-link">Read</a>` : ''}
              </div>
            </div>
          `).join('')}
        </div>
      `;
    } catch (e) {
      console.warn('Failed to load WHO outbreaks:', e);
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">Unable to load WHO data</div>';
    }
  },

  renderNewsSignal(data) {
    const container = document.getElementById('intelligence-signal-content');
    if (!container) return;

    if (!data.topRiskCountry) {
      container.innerHTML = '<div class="text-muted">No top risk country data</div>';
      return;
    }

    const levelClass = (data.topRiskLevel || 'stable').toLowerCase();
    const drivers = data.topRiskDrivers ? data.topRiskDrivers.join(', ') : '';

    container.innerHTML = `
      <div class="news-signal-header">
        <span class="news-signal-country">${data.topRiskCountry} (${data.topRiskIso3})</span>
        <span class="badge ${levelClass === 'critical' ? 'famine' : levelClass === 'alert' ? 'critical' : 'high'}">${data.topRiskLevel}</span>
        <span class="news-signal-score">Score: ${data.topRiskScore}</span>
      </div>
      ${drivers ? `<div class="news-signal-drivers">Drivers: ${drivers}</div>` : ''}
      ${data.topRiskHeadlines && data.topRiskHeadlines.length > 0 ? `
        <div style="margin-top: var(--space-sm);">
          <div class="humanitarian-label">GDELT Headlines</div>
          <ul class="news-headlines-list">
            ${data.topRiskHeadlines.map(h => `
              <li>
                ${h.url ? `<a href="${h.url}" target="_blank" class="news-headline-link">${h.title}</a>` : h.title}
                ${h.source ? `<span class="news-source">${h.source}</span>` : ''}
              </li>
            `).join('')}
          </ul>
        </div>
      ` : ''}
    `;
  },

  renderReliefWebReports(data) {
    const container = document.getElementById('reliefweb-reports-list');
    if (!container) return;

    if (!data.reliefWebReports || data.reliefWebReports.length === 0) {
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">No reports available</div>';
      return;
    }

    container.innerHTML = `
      <ul class="humanitarian-reports-list">
        ${data.reliefWebReports.map(r => `
          <li>
            <a href="${r.url}" target="_blank" class="humanitarian-link">${r.title}</a>
            <div class="humanitarian-meta">
              <span class="humanitarian-source">${r.source || 'ReliefWeb'}</span>
              <span class="humanitarian-country">${r.countryName}</span>
              ${r.date ? `<span class="humanitarian-date">${r.date}</span>` : ''}
            </div>
          </li>
        `).join('')}
      </ul>
    `;
  },

  renderGDELTCoverage(data) {
    const container = document.getElementById('gdelt-media-container');
    if (!container) return;

    if (!data.mediaSpikes || data.mediaSpikes.length === 0) {
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-lg);">All countries at normal coverage levels</div>';
      return;
    }

    const rows = data.mediaSpikes.map(spike => {
      const levelClass = spike.spikeLevel === 'CRITICAL' ? 'famine' :
                         spike.spikeLevel === 'HIGH' ? 'critical' :
                         spike.spikeLevel === 'ELEVATED' ? 'high' : 'medium';
      const zScore = spike.zScore != null ? spike.zScore.toFixed(1) : '-';
      const articles = spike.articles7d != null ? spike.articles7d : '-';
      return `
        <tr>
          <td><strong>${spike.countryName || 'Unknown'}</strong></td>
          <td class="value-cell"><span class="badge ${levelClass}">${spike.spikeLevel || '-'}</span></td>
          <td class="value-cell">${zScore}</td>
          <td class="value-cell">${articles}</td>
        </tr>
      `;
    }).join('');

    container.innerHTML = `
      <table class="data-table">
        <thead>
          <tr>
            <th>Country</th>
            <th>Level</th>
            <th>Z-Score</th>
            <th>7d Articles</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    `;
  },

  // Legacy methods for backward compatibility (redirect to centralized)
  async loadNewsSignal() { await this.init(); },
  async loadReliefWebReports() { /* handled by init */ },
  async loadGDELTCoverage() { /* handled by init */ }
};

// ============================================
// ACTIVE SITUATIONS (TOP OF INTELLIGENCE)
// ============================================
const SituationManager = {
  loaded: false,
  data: null,
  currentFilter: 'critical,high', // Default: Critical + High
  isClaudeData: false, // Track if we're showing Claude data

  async init() {
    if (this.loaded && this.data) {
      this.renderAll(this.data);
      return;
    }

    this.showLoading();
    this.initFilterChips();

    try {
      // Try Claude cached results first (they're more accurate)
      const claudeResponse = await fetch('/api/situations/claude-cached', { cache: 'no-store' });
      if (claudeResponse.ok) {
        const claudeData = await claudeResponse.json();
        console.log('Claude cached situations:', claudeData);

        // If Claude has cached results with situations, use them
        if (claudeData && claudeData.status === 'OK' && claudeData.situations && claudeData.situations.length > 0) {
          this.data = this.transformClaudeData(claudeData);
          this.isClaudeData = true;
          this.renderAll(this.data);
          this.loaded = true;
          return;
        }
      }

      // Fall back to keyword-based
      const response = await fetch('/api/situations/active');
      if (!response.ok) {
        this.showError('Unable to detect situations');
        return;
      }

      const data = await response.json();
      console.log('Keyword situations data:', data);

      if (data && data.status === 'WARMING_UP') {
        this.showLoading();
        setTimeout(() => this.init(), 30000);
        return;
      }

      if (data && data.status === 'READY') {
        this.data = data;
        this.isClaudeData = false;
        this.renderAll(data);
        this.loaded = true;
      } else if (data && data.situations && data.situations.length === 0) {
        // Show prompt to use Claude detection
        this.showClaudePrompt();
      }
    } catch (error) {
      console.error('Failed to load situations:', error);
      this.showError('Unable to detect situations');
    }
  },

  // Transform Claude data to match the expected format
  transformClaudeData(claudeData) {
    return {
      status: 'READY',
      timestamp: claudeData.generatedAt,
      date: new Date().toISOString().split('T')[0],
      todaySummary: [claudeData.globalContext || 'Claude-analyzed situations'],
      situations: claudeData.situations.map(s => ({
        iso3: s.iso3,
        countryName: s.countryName,
        situationType: s.type,
        situationLabel: s.type.replace(/_/g, ' '),
        summary: s.summary,
        severity: s.severity,
        riskScore: 0, // Not available in Claude data
        articlesCount: 0, // Not available in Claude data
        reportsCount: 0, // Not available in Claude data
        signals: [s.summary],
        evidence: s.evidence ? s.evidence.map(e => ({
          source: 'Claude',
          title: e,
          url: null,
          publisher: 'AI Analysis'
        })) : [],
        trajectory: s.trajectory,
        confidence: s.confidence
      })),
      totalSituations: claudeData.situations.length,
      isClaudeData: true,
      model: claudeData.model,
      analyzedCountries: claudeData.analyzedCountries
    };
  },

  showClaudePrompt() {
    const list = document.getElementById('situations-list');
    const count = document.getElementById('situations-count');

    if (list) {
      list.innerHTML = `
        <div style="padding: var(--space-lg); text-align: center;">
          <div style="color: var(--text-secondary); margin-bottom: var(--space-md);">No situations detected with keyword matching</div>
          <div style="font-size: 0.8rem; color: var(--text-muted);">Click "Detect Situations (Claude)" for AI-powered analysis</div>
        </div>
      `;
    }
    if (count) count.textContent = '0 detected';
  },

  initFilterChips() {
    const chips = document.querySelectorAll('.situation-filter-chip');
    chips.forEach(chip => {
      chip.addEventListener('click', () => {
        chips.forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        this.currentFilter = chip.dataset.filter;
        if (this.data) this.renderList(this.data);
      });
    });
  },

  showLoading() {
    const list = document.getElementById('situations-list');
    const count = document.getElementById('situations-count');

    if (list) Utils.showSkeleton(list, 'alert', 4);
    if (count) count.textContent = '...';
  },

  showError(msg) {
    const list = document.getElementById('situations-list');
    if (list) list.innerHTML = `<div class="text-muted" style="padding: var(--space-md);">${msg}</div>`;
  },

  renderAll(data) {
    this.updateFilterCounts(data);
    this.renderList(data);
    this.updateCount(data);
  },

  updateFilterCounts(data) {
    const situations = data.situations || [];

    // Count by severity
    const counts = { CRITICAL: 0, HIGH: 0, ELEVATED: 0, WATCH: 0 };
    situations.forEach(s => {
      const severity = (s.severity || 'WATCH').toUpperCase();
      if (counts.hasOwnProperty(severity)) counts[severity]++;
    });

    // Update filter chip counts
    const allEl = document.getElementById('count-all');
    const critHighEl = document.getElementById('count-critical-high');
    const criticalEl = document.getElementById('count-critical');
    const highEl = document.getElementById('count-high');
    const elevatedEl = document.getElementById('count-elevated');
    const watchEl = document.getElementById('count-watch');

    if (allEl) allEl.textContent = situations.length;
    if (critHighEl) critHighEl.textContent = counts.CRITICAL + counts.HIGH;
    if (criticalEl) criticalEl.textContent = counts.CRITICAL;
    if (highEl) highEl.textContent = counts.HIGH;
    if (elevatedEl) elevatedEl.textContent = counts.ELEVATED;
    if (watchEl) watchEl.textContent = counts.WATCH;
  },

  renderList(data) {
    const container = document.getElementById('situations-list');
    if (!container) return;

    const situations = data.situations || [];
    if (situations.length === 0) {
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">No active situations</div>';
      return;
    }

    // Apply filter
    const filterValues = this.currentFilter.split(',').map(f => f.toUpperCase());
    const filtered = this.currentFilter === 'all'
      ? situations
      : situations.filter(s => filterValues.includes((s.severity || 'WATCH').toUpperCase()));

    if (filtered.length === 0) {
      container.innerHTML = '<div class="text-muted" style="padding: var(--space-md);">No situations matching filter</div>';
      return;
    }

    const isClaudeData = this.data?.isClaudeData || false;

    container.innerHTML = filtered.map((situation, idx) => {
      const severityClass = (situation.severity || '').toLowerCase();
      const riskScore = situation.riskScore || 0;
      const articlesCount = situation.articlesCount || 0;
      const reportsCount = situation.reportsCount || 0;

      // Handle 250+ cap
      const articlesDisplay = articlesCount >= 250 ? '250+' : articlesCount;
      const articlesCapped = articlesCount >= 250;

      // Evidence count
      const evidenceCount = (situation.evidence || []).length;

      // Claude-specific fields
      const trajectory = situation.trajectory || '';
      const confidence = situation.confidence || '';
      const summary = situation.summary || '';

      return `
        <div class="situation-card ${severityClass}" data-severity="${situation.severity}">
          <div class="situation-card-header">
            <div>
              <div class="situation-title-row">
                <h4 class="situation-country">${Utils.escapeHtml(situation.countryName)}</h4>
                <span class="situation-label">— ${situation.situationLabel || situation.situationType}</span>
              </div>
              ${summary ? `<div class="situation-summary" style="font-size: 0.85rem; color: var(--text-secondary); margin: 4px 0 8px;">${Utils.escapeHtml(summary)}</div>` : ''}
              <div class="situation-badges">
                <span class="situation-badge severity ${severityClass}">${situation.severity}</span>
                ${situation.trajectory ? `<span class="situation-badge trajectory ${(situation.trajectory || '').toLowerCase()}" title="${Utils.escapeHtml(situation.trajectoryReason || '')}">${situation.trajectory}</span>` : ''}
                ${isClaudeData && confidence ? `<span class="situation-badge confidence">${confidence} conf.</span>` : ''}
                ${!isClaudeData && riskScore > 0 ? `<span class="situation-badge">Risk ${riskScore}</span>` : ''}
                ${!isClaudeData && articlesCount > 0 ? `<span class="situation-badge ${articlesCapped ? 'capped' : ''}">${articlesDisplay} articles${articlesCapped ? ' (capped)' : ''}</span>` : ''}
                ${!isClaudeData && reportsCount > 0 ? `<span class="situation-badge">${reportsCount} reports</span>` : ''}
              </div>
              ${situation.relatedCountries && situation.relatedCountries.length > 0 ? `
                <div class="situation-related">Also affected: ${situation.relatedCountries.map(c => Utils.escapeHtml(c)).join(', ')}</div>
              ` : ''}
            </div>
          </div>
          ${evidenceCount > 0 ? `
            <div class="situation-evidence" id="evidence-${idx}">
              <button class="situation-evidence-toggle" onclick="SituationManager.toggleEvidence(${idx})">
                <span class="toggle-icon">▼</span>
                <span>Evidence (${evidenceCount})</span>
              </button>
              <div class="situation-evidence-content">
                ${situation.evidence.slice(0, 6).map(e => `
                  <div class="evidence-item">
                    <span class="evidence-source ${(e.source || 'claude').toLowerCase()}">${e.source || 'Evidence'}</span>
                    ${e.url ? `<a href="${e.url}" target="_blank" class="evidence-link">${Utils.escapeHtml(e.title)}</a>` : `<span class="evidence-text">${Utils.escapeHtml(e.title)}</span>`}
                  </div>
                `).join('')}
              </div>
            </div>
          ` : ''}
        </div>
      `;
    }).join('');
  },

  toggleEvidence(idx) {
    const el = document.getElementById(`evidence-${idx}`);
    if (el) el.classList.toggle('open');
  },

  updateCount(data) {
    const count = document.getElementById('situations-count');
    if (count) {
      const num = data.totalSituations || 0;
      count.textContent = `${num} detected`;
      count.className = num > 5 ? 'badge famine' : num > 2 ? 'badge critical' : 'badge';
    }
  }
};

// ============================================
// NEWS FEED MANAGER (Two-Column: ReliefWeb | Media)
// ============================================
const NewsFeedManager = {
  loaded: false,
  feedData: null,
  currentRegion: '',
  currentTopic: '',

  async init() {
    if (this.loaded && this.feedData) {
      this.renderFeed(this.feedData);
      return;
    }

    this.setupFilters();
    await this.loadFeed();
  },

  setupFilters() {
    const regionFilter = document.getElementById('news-region-filter');
    const topicFilter = document.getElementById('news-topic-filter');
    const refreshBtn = document.getElementById('news-refresh-btn');

    if (regionFilter) {
      regionFilter.addEventListener('change', () => {
        this.currentRegion = regionFilter.value;
        this.loadFeed();
      });
    }

    if (topicFilter) {
      topicFilter.addEventListener('change', () => {
        this.currentTopic = topicFilter.value;
        this.loadFeed();
      });
    }

    if (refreshBtn) {
      refreshBtn.addEventListener('click', () => {
        this.loaded = false;
        this.loadFeed();
      });
    }
  },

  async loadFeed() {
    const reliefwebCol = document.getElementById('news-col-reliefweb');
    const mediaCol = document.getElementById('news-col-media');
    if (!reliefwebCol || !mediaCol) return;

    const loadingHtml = '<div class="loading-placeholder" style="padding: var(--space-lg); text-align: center; color: var(--text-secondary);">Loading... <span class="loading-dots"></span></div>';
    reliefwebCol.innerHTML = loadingHtml;
    mediaCol.innerHTML = loadingHtml;

    try {
      let url = '/api/news-feed';
      const params = [];
      if (this.currentRegion) params.push(`region=${this.currentRegion}`);
      if (this.currentTopic) params.push(`topic=${this.currentTopic}`);
      if (params.length > 0) url += '?' + params.join('&');

      const response = await fetch(url);
      const data = await response.json();

      this.feedData = data;
      this.loaded = true;
      this.renderFeed(data);

    } catch (error) {
      console.error('Error loading news feed:', error);
      const errorHtml = '<div class="news-empty">Failed to load. Try refreshing.</div>';
      reliefwebCol.innerHTML = errorHtml;
      mediaCol.innerHTML = errorHtml;
    }
  },

  renderFeed(data) {
    const reliefwebCol = document.getElementById('news-col-reliefweb');
    const mediaCol = document.getElementById('news-col-media');
    if (!reliefwebCol || !mediaCol) return;

    // Render ReliefWeb column
    if (data.reliefweb && data.reliefweb.length > 0) {
      reliefwebCol.innerHTML = data.reliefweb.map(item => this.renderNewsItem(item)).join('');
    } else {
      reliefwebCol.innerHTML = '<div class="news-empty">No humanitarian reports for these filters.</div>';
    }

    // Render Media column
    if (data.media && data.media.length > 0) {
      mediaCol.innerHTML = data.media.map(item => this.renderNewsItem(item)).join('');
    } else {
      mediaCol.innerHTML = '<div class="news-empty">No media coverage for these filters.</div>';
    }
  },

  renderNewsItem(item) {
    const topics = (item.topics || []).filter(t => t !== 'general' && t !== 'humanitarian').slice(0, 2);
    const regionClass = (item.region || 'global').toLowerCase().replace(/\s+/g, '-');

    return `
      <div class="news-item">
        <div class="news-item-top">
          <span class="news-item-region ${regionClass}">${item.region || 'Global'}</span>
          ${item.countryName ? `<span class="news-item-country">${Utils.escapeHtml(item.countryName)}</span>` : ''}
          ${item.timeAgo ? `<span class="news-item-time">${item.timeAgo}</span>` : ''}
        </div>
        <div class="news-item-title">
          ${item.url
            ? `<a href="${item.url}" target="_blank" rel="noopener">${Utils.escapeHtml(item.title)}</a>`
            : Utils.escapeHtml(item.title)
          }
        </div>
        <div class="news-item-bottom">
          <span class="news-item-source ${item.sourceType ? item.sourceType.toLowerCase() : ''}">${Utils.escapeHtml(item.source || '')}</span>
          ${item.format ? `<span class="news-item-format">${Utils.escapeHtml(item.format)}</span>` : ''}
          ${topics.length > 0 ? topics.map(t => `<span class="news-item-tag">${t}</span>`).join('') : ''}
        </div>
      </div>
    `;
  }
};

// ============================================
// TOPIC REPORT GENERATOR
// ============================================
const TopicReportGenerator = {
  currentTopic: 'migration',
  currentRegion: '',
  currentPeriod: 7,
  reportData: null,

  init() {
    this.initTopicChips();
    this.initControls();
  },

  initTopicChips() {
    const chips = document.querySelectorAll('#report-topic-chips .topic-chip');
    chips.forEach(chip => {
      chip.addEventListener('click', () => {
        chips.forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        this.currentTopic = chip.dataset.topic;
      });
    });
  },

  initControls() {
    const regionSelect = document.getElementById('report-region-select');
    const periodSelect = document.getElementById('report-period-select');
    const generateBtn = document.getElementById('btn-generate-report');

    if (regionSelect) {
      regionSelect.addEventListener('change', (e) => {
        this.currentRegion = e.target.value;
      });
    }

    if (periodSelect) {
      periodSelect.addEventListener('change', (e) => {
        this.currentPeriod = parseInt(e.target.value);
      });
    }

    if (generateBtn) {
      generateBtn.addEventListener('click', () => this.generateReport());
    }
  },

  async generateReport() {
    const btn = document.getElementById('btn-generate-report');
    const status = document.getElementById('report-status');
    const output = document.getElementById('topic-report-output');

    // Show loading
    if (btn) {
      btn.querySelector('.btn-text').style.display = 'none';
      btn.querySelector('.btn-loading').style.display = 'inline';
      btn.disabled = true;
    }
    if (status) status.textContent = 'Generating...';

    try {
      const response = await fetch(`/api/intelligence/topic-report?topic=${this.currentTopic}&region=${this.currentRegion}&days=${this.currentPeriod}`);

      if (!response.ok) {
        throw new Error('Failed to generate report');
      }

      const data = await response.json();
      this.reportData = data;

      try {
        this.renderReport(data);
        if (output) output.style.display = 'block';
      } catch (renderError) {
        console.error('Render error:', renderError);
        alert('Error rendering report: ' + renderError.message);
      }

      if (status) status.textContent = 'Ready';

    } catch (error) {
      console.error('Failed to generate report:', error);
      if (status) status.textContent = 'Error';
      alert('Failed to generate report. Please try again.');
    } finally {
      if (btn) {
        btn.querySelector('.btn-text').style.display = 'inline';
        btn.querySelector('.btn-loading').style.display = 'none';
        btn.disabled = false;
      }
    }
  },

  renderReport(data) {
    console.log('Rendering report:', data);

    // Title with trend
    const title = document.getElementById('report-title');
    if (title) {
      const topicLabel = this.currentTopic.charAt(0).toUpperCase() + this.currentTopic.slice(1).replace('-', ' ');
      const regionLabel = this.currentRegion ? ` - ${this.currentRegion.toUpperCase()}` : '';
      title.textContent = `${topicLabel} Report${regionLabel}`;
    }

    // Trend badge - handle signal_only honestly
    const trendBadge = document.getElementById('report-trend');
    if (trendBadge && data.overallTrend) {
      const trendDisplay = {
        increasing: { icon: '↑', label: 'INCREASING', class: 'trend-up' },
        decreasing: { icon: '↓', label: 'DECREASING', class: 'trend-down' },
        stable: { icon: '→', label: 'STABLE', class: 'trend-stable' },
        signal_only: { icon: '📰', label: 'SIGNAL ONLY', class: 'trend-signal' },
        insufficient_data: { icon: '—', label: 'NO DATA', class: 'trend-insufficient' }
      };
      const td = trendDisplay[data.overallTrend] || trendDisplay.signal_only;
      trendBadge.textContent = `${td.icon} ${td.label}`;
      trendBadge.className = `report-trend-badge ${td.class}`;
    }

    // Period label
    const periodLabel = document.getElementById('report-period-label');
    if (periodLabel && data.periodStart && data.periodEnd) {
      periodLabel.textContent = `${data.periodStart} - ${data.periodEnd}`;
    }

    // Summary
    const summary = document.getElementById('report-summary');
    if (summary && data.regionalSummary) {
      const rs = data.regionalSummary;
      summary.textContent = `${rs.totalCountries} countries | ${rs.totalMedia} media | ${rs.totalReports} reports`;
    }

    // Regional Stats with Coverage Quality
    const stats = document.getElementById('report-stats');
    if (stats && data.regionalSummary) {
      const rs = data.regionalSummary;
      let statsHtml = '';

      // Stock totals
      if (rs.totalIdps) {
        statsHtml += `<div class="stat-pill"><span class="stat-value">${rs.totalIdps}</span><span class="stat-label">IDPs (stock)</span></div>`;
      }
      if (rs.totalRefugees) {
        statsHtml += `<div class="stat-pill"><span class="stat-value">${rs.totalRefugees}</span><span class="stat-label">Refugees (stock)</span></div>`;
      }

      // Coverage quality indicator
      const qualityClass = rs.coverageQuality === 'HIGH' ? 'quality-high' :
                          rs.coverageQuality === 'MEDIUM' ? 'quality-med' : 'quality-low';
      const flowStatus = rs.hasFlowData ? 'Flow: ✓' : 'Flow: n/a';
      statsHtml += `<div class="coverage-quality ${qualityClass}">
        <span class="quality-label">Coverage:</span>
        <span class="quality-value">${rs.coverageQuality}</span>
        <span class="quality-detail">${flowStatus} | Op: ${rs.totalReports} | Media: ${rs.totalMedia}</span>
      </div>`;

      stats.innerHTML = statsHtml;
    }

    // Key Developments (AI-generated)
    const developments = document.getElementById('report-developments');
    if (developments && data.keyDevelopments && data.keyDevelopments.length > 0) {
      developments.innerHTML = data.keyDevelopments.map(dev => {
        const trendIcon = dev.trend === 'increasing' ? '↑' : dev.trend === 'decreasing' ? '↓' : '→';
        const evidenceIcon = dev.evidenceType === 'flow' ? '📊' : dev.evidenceType === 'operational' ? '📋' : '📰';
        return `
          <li class="development-item ${dev.trend || ''}">
            <span class="dev-icon">${evidenceIcon}</span>
            <span class="dev-text">${Utils.escapeHtml(dev.bullet)}</span>
            ${dev.country ? `<span class="dev-trend trend-${dev.trend}">${trendIcon}</span>` : ''}
          </li>
        `;
      }).join('');
    } else if (developments) {
      developments.innerHTML = '<li class="text-muted">No key developments found for this period.</li>';
    }

    // Country Matrix (Signal, Stock, Flow Δ, Trend, Conf)
    const matrix = document.querySelector('#report-country-matrix tbody');
    if (matrix && data.countryMatrix) {
      matrix.innerHTML = data.countryMatrix.map(c => {
        // Signal = media + reports combined
        const signalCount = c.signalCount || (c.mediaCount + c.reportsCount);
        const signalDisplay = signalCount > 0 ? signalCount : '—';

        // Stock data (IDPs/refugees - NOT flow)
        const stockDisplay = c.hasStock ? c.stockData : '—';

        // Flow Δ (WoW/MoM change) - explicit about missing data
        const flowDeltaDisplay = c.hasFlowDelta
          ? c.flowDelta
          : `<span class="no-data" title="Flow monitoring data not available">—</span>`;

        // Trend: only show arrow if we have flow delta
        let trendIcon, trendClass;
        if (c.trend === 'increasing') {
          trendIcon = '↑'; trendClass = 'trend-up';
        } else if (c.trend === 'decreasing') {
          trendIcon = '↓'; trendClass = 'trend-down';
        } else if (c.trend === 'stable') {
          trendIcon = '→'; trendClass = 'trend-stable';
        } else {
          // No trend data - be explicit
          trendIcon = '—'; trendClass = 'trend-none';
        }

        // Confidence - handle "signal_only" as distinct from low
        let confClass, confLabel;
        if (c.confidence === 'high') {
          confClass = 'conf-high'; confLabel = 'HIGH';
        } else if (c.confidence === 'medium') {
          confClass = 'conf-med'; confLabel = 'MED';
        } else if (c.confidence === 'signal_only') {
          confClass = 'conf-signal'; confLabel = 'SIGNAL';  // Honest: we have signal, not trend
        } else {
          confClass = 'conf-low'; confLabel = 'LOW';
        }

        return `
          <tr>
            <td class="country-name">${Utils.escapeHtml(c.country)}</td>
            <td class="num signal-cell">${signalDisplay}</td>
            <td class="stock-cell">${stockDisplay}</td>
            <td class="flow-delta-cell">${flowDeltaDisplay}</td>
            <td class="trend-cell ${trendClass}" title="${c.hasFlowDelta ? 'Based on flow data' : 'No flow data available'}">${trendIcon}</td>
            <td class="conf-cell"><span class="conf-badge ${confClass}" title="${c.confidence === 'signal_only' ? 'Signal detected, no trend data' : ''}">${confLabel}</span></td>
          </tr>
        `;
      }).join('');
    }

    // Sources
    const sources = document.getElementById('report-sources');
    if (sources && data.sources) {
      sources.innerHTML = data.sources.slice(0, 20).map(s => `
        <div class="source-item">
          <span class="source-badge ${s.type.toLowerCase()}">${s.type}</span>
          <a href="${s.url}" target="_blank" class="source-link">${Utils.escapeHtml(s.title)}</a>
          <span class="source-date">${s.date}</span>
        </div>
      `).join('');
    }
  },

  exportReport() {
    if (!this.reportData) return;

    const topicLabel = this.currentTopic.charAt(0).toUpperCase() + this.currentTopic.slice(1).replace('-', ' ');
    const regionLabel = this.currentRegion ? this.currentRegion.toUpperCase() : 'Global';

    let text = `${topicLabel} REPORT - ${regionLabel}\n`;
    text += `Period: Last ${this.currentPeriod} days\n`;
    text += `Generated: ${new Date().toLocaleDateString()}\n\n`;
    text += `KEY DEVELOPMENTS:\n`;

    if (this.reportData.developments) {
      this.reportData.developments.forEach(dev => {
        text += `• ${dev.country}: ${dev.summary}\n`;
      });
    }

    text += `\nCOUNTRY COVERAGE:\n`;
    if (this.reportData.countryCoverage) {
      this.reportData.countryCoverage.forEach(c => {
        text += `${c.country} (${c.count} mentions)\n`;
      });
    }

    // Download as text file
    const blob = new Blob([text], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${topicLabel.toLowerCase()}-report-${regionLabel.toLowerCase()}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  },

  copyReport() {
    if (!this.reportData) return;

    const topicLabel = this.currentTopic.charAt(0).toUpperCase() + this.currentTopic.slice(1).replace('-', ' ');
    const regionLabel = this.currentRegion ? this.currentRegion.toUpperCase() : 'Global';

    let text = `${topicLabel} REPORT - ${regionLabel}\n\n`;
    text += `KEY DEVELOPMENTS:\n`;

    if (this.reportData.developments) {
      this.reportData.developments.forEach(dev => {
        text += `• ${dev.country}: ${dev.summary}\n`;
      });
    }

    navigator.clipboard.writeText(text).then(() => {
      alert('Report copied to clipboard!');
    });
  }
};

// ============================================
// DAILY INTELLIGENCE BRIEFING
// ============================================
const DailyBriefingManager = {
  loaded: false,
  briefingData: null,
  currentRegion: 'all',

  async init() {
    if (this.loaded && this.briefingData) {
      this.renderAll(this.briefingData);
      return;
    }

    this.showLoading();
    this.initRegionTabs();

    try {
      const response = await fetch('/api/briefing/daily');
      if (!response.ok) {
        console.error('Briefing response not ok:', response.status);
        this.showError('Unable to load briefing');
        return;
      }

      const data = await response.json();
      console.log('Daily briefing data:', data);

      if (data && data.status === 'WARMING_UP') {
        this.showWarmingUp();
        // Single retry after 30s
        setTimeout(() => this.init(), 30000);
        return;
      }

      if (data && data.status === 'READY') {
        this.briefingData = data;
        this.renderAll(data);
        this.loaded = true;
      } else {
        this.showWarmingUp();
      }
    } catch (error) {
      console.error('Failed to load daily briefing:', error);
      this.showError('Unable to load briefing');
    }
  },

  initRegionTabs() {
    const tabs = document.querySelectorAll('#briefing-region-tabs .region-tab');
    tabs.forEach(tab => {
      tab.addEventListener('click', () => {
        tabs.forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        this.currentRegion = tab.dataset.region;
        if (this.briefingData) {
          this.renderRegionalContent(this.briefingData);
        }
      });
    });
  },

  showLoading() {
    const container = document.getElementById('briefing-top-headlines');
    if (container) {
      Utils.showSkeleton(container, 'news', 4);
    }
  },

  showError(message) {
    const container = document.getElementById('briefing-top-headlines');
    if (container) {
      container.innerHTML = `<div class="briefing-empty">${message}</div>`;
    }
  },

  showWarmingUp() {
    const container = document.getElementById('briefing-top-headlines');
    if (container) {
      container.innerHTML = `
        <div class="briefing-empty">
          <div>Preparing intelligence briefing...</div>
          <div class="text-muted" style="font-size: 0.8em; margin-top: 4px;">Data will appear shortly</div>
        </div>
      `;
    }
  },

  renderAll(data) {
    // Update date badge
    const dateBadge = document.getElementById('briefing-date');
    if (dateBadge && data.date) {
      dateBadge.textContent = data.date;
    }

    // Render executive summary + priority alerts + top headlines
    this.renderTopSection(data);

    // Render regional content
    this.renderRegionalContent(data);
  },

  renderTopSection(data) {
    const container = document.getElementById('briefing-top-headlines');
    if (!container) return;

    let html = '';

    // Executive Summary
    if (data.executiveSummary && data.executiveSummary.length > 0) {
      html += `<div class="briefing-exec-summary">
        <div class="briefing-exec-title">Executive Summary</div>
        ${data.executiveSummary.map(line => `<div class="briefing-exec-line">${Utils.escapeHtml(line)}</div>`).join('')}
      </div>`;
    }

    // Priority Alerts
    if (data.priorityAlerts && data.priorityAlerts.length > 0) {
      html += `<div class="briefing-priority-section">
        <div class="briefing-priority-title">Priority Alerts <span class="briefing-priority-count">${data.priorityAlerts.length}</span></div>
        ${data.priorityAlerts.map(item => `
          <div class="briefing-priority-item">
            <a href="${Utils.sanitizeUrl(item.url)}" target="_blank" rel="noopener noreferrer" class="briefing-priority-link">
              ${Utils.escapeHtml(item.title)}
            </a>
            <div class="briefing-priority-meta">
              <span class="briefing-priority-source">${Utils.escapeHtml(item.source || '')}</span>
              ${item.topics ? item.topics.slice(0, 2).map(t => `<span class="topic-tag ${t.toLowerCase()}">${t.replace('_', ' ')}</span>`).join('') : ''}
            </div>
          </div>
        `).join('')}
      </div>`;
    }

    // Top Headlines (monitoring)
    if (data.topHeadlines && data.topHeadlines.length > 0) {
      html += `<div class="briefing-headlines-title">Latest Updates</div>
        ${data.topHeadlines.map(item => `
          <div class="briefing-headline-item">
            <a href="${Utils.sanitizeUrl(item.url)}" target="_blank" rel="noopener noreferrer" class="briefing-headline-link">
              ${Utils.escapeHtml(item.title)}
            </a>
            <span class="briefing-headline-source">${Utils.escapeHtml(item.source || '')}</span>
          </div>
        `).join('')}`;
    }

    if (!html) {
      html = '<div class="briefing-empty">No headlines available</div>';
    }

    container.innerHTML = html;
  },

  renderRegionalContent(data) {
    const container = document.getElementById('briefing-region-content');
    if (!container) return;

    if (!data.regionalBriefings || data.regionalBriefings.length === 0) {
      container.innerHTML = '<div class="briefing-empty">No regional data available</div>';
      return;
    }

    // Filter by selected region
    let regions = data.regionalBriefings;
    if (this.currentRegion !== 'all') {
      regions = regions.filter(r => r.regionCode === this.currentRegion);
    }

    if (regions.length === 0) {
      container.innerHTML = '<div class="briefing-empty">No news for this region</div>';
      return;
    }

    container.innerHTML = regions.map(region => `
      <div class="region-section">
        <div class="region-section-header">
          <span class="region-section-name">${region.regionName}</span>
          <span class="region-section-count">${region.itemCount} items</span>
        </div>
        <ul class="region-news-list">
          ${region.newsItems.slice(0, 8).map(item => `
            <li class="region-news-item">
              <a href="${item.url || '#'}" target="_blank" class="region-news-title">
                ${Utils.escapeHtml(item.title)}
              </a>
              <div class="region-news-meta">
                <span class="region-news-source">${item.source || ''}</span>
                ${item.topics && item.topics.length > 0 ? `
                  <div class="region-news-topics">
                    ${item.topics.slice(0, 3).map(topic => `
                      <span class="topic-tag ${topic.toLowerCase()}">${topic.replace('_', ' ')}</span>
                    `).join('')}
                  </div>
                ` : ''}
              </div>
            </li>
          `).join('')}
        </ul>
      </div>
    `).join('');
  }
};

// ============================================
// TOPIC SEARCH - Intelligence by Theme
// ============================================
const TopicSearch = {
  currentTopic: null,
  currentRegion: null,

  init() {
    // Initialize topic chips
    const chips = document.querySelectorAll('.topic-chip');
    chips.forEach(chip => {
      chip.addEventListener('click', () => {
        this.selectTopic(chip.dataset.topic, chip);
      });
    });

    // Initialize region select
    const regionSelect = document.getElementById('region-select');
    if (regionSelect) {
      regionSelect.addEventListener('change', () => {
        this.currentRegion = regionSelect.value;
        if (this.currentTopic) {
          this.search();
        }
      });
    }
  },

  selectTopic(topic, chipElement) {
    // Update active state
    document.querySelectorAll('.topic-chip').forEach(c => c.classList.remove('active'));
    if (chipElement) chipElement.classList.add('active');

    this.currentTopic = topic;
    this.search();
  },

  async search() {
    if (!this.currentTopic) return;

    const resultsContainer = document.getElementById('topic-search-results');
    if (!resultsContainer) return;

    // Show loading state
    resultsContainer.style.display = 'block';
    document.getElementById('topic-results-title').textContent = 'Loading...';
    document.getElementById('topic-results-summary').innerHTML = '<div class="text-muted">Searching...</div>';

    try {
      let url = `/api/intelligence/search?topic=${this.currentTopic}`;
      if (this.currentRegion) {
        url += `&region=${this.currentRegion}`;
      }

      const response = await fetch(url);
      const data = await response.json();

      this.renderResults(data);
    } catch (error) {
      console.error('Topic search failed:', error);
      document.getElementById('topic-results-title').textContent = 'Error';
      document.getElementById('topic-results-summary').innerHTML = '<div class="text-muted">Failed to load results</div>';
    }
  },

  renderResults(data) {
    // Update title
    let title = data.topicDisplayName || data.topic;
    if (data.regionDisplayName) {
      title += ` - ${data.regionDisplayName}`;
    }
    document.getElementById('topic-results-title').textContent = title;

    // Update summary
    const summaryEl = document.getElementById('topic-results-summary');
    summaryEl.innerHTML = `
      <div class="topic-stat">
        <div class="topic-stat-value">${data.headlines?.length || 0}</div>
        <div class="topic-stat-label">Headlines</div>
      </div>
      <div class="topic-stat">
        <div class="topic-stat-value">${data.officialReports?.length || 0}</div>
        <div class="topic-stat-label">Reports</div>
      </div>
      <div class="topic-stat">
        <div class="topic-stat-value">${data.trendDirection || 'N/A'}</div>
        <div class="topic-stat-label">Activity</div>
      </div>
    `;

    // Render headlines
    const headlinesEl = document.getElementById('topic-headlines');
    if (data.headlines && data.headlines.length > 0) {
      headlinesEl.innerHTML = data.headlines.map(h => `
        <div class="topic-headline-item">
          <div class="topic-headline-title">
            ${h.url ? `<a href="${h.url}" target="_blank">${h.title}</a>` : h.title}
          </div>
          <div class="topic-headline-meta">
            <span>${h.countryName || h.countryIso3}</span>
            <span>${h.source || ''}</span>
          </div>
        </div>
      `).join('');
    } else {
      headlinesEl.innerHTML = '<div class="text-muted">No headlines found</div>';
    }

    // Render reports
    const reportsEl = document.getElementById('topic-reports');
    if (data.officialReports && data.officialReports.length > 0) {
      reportsEl.innerHTML = data.officialReports.map(r => `
        <div class="topic-report-item">
          <div class="topic-report-title">
            ${r.url ? `<a href="${r.url}" target="_blank">${r.title}</a>` : r.title}
          </div>
          <div class="topic-report-meta">
            <span>${r.countryName || r.countryIso3}</span>
            <span>${r.source || ''}</span>
            <span>${r.date || ''}</span>
          </div>
        </div>
      `).join('');
    } else {
      reportsEl.innerHTML = '<div class="text-muted">No official reports found</div>';
    }

    // Render country breakdown
    const countryEl = document.getElementById('topic-country-stats');
    if (data.countryBreakdown && Object.keys(data.countryBreakdown).length > 0) {
      countryEl.innerHTML = Object.values(data.countryBreakdown).map(c => {
        const spikeClass = c.spikeLevel === 'CRITICAL' ? 'spike-critical' :
                          c.spikeLevel === 'HIGH' ? 'spike-high' : '';
        return `
          <div class="topic-country-chip ${spikeClass}">
            <span class="country-name">${c.countryName}</span>
            <span class="article-count">${c.articleCount} articles</span>
          </div>
        `;
      }).join('');
    } else {
      countryEl.innerHTML = '<div class="text-muted">No country data</div>';
    }
  },

  clearResults() {
    const resultsContainer = document.getElementById('topic-search-results');
    if (resultsContainer) {
      resultsContainer.style.display = 'none';
    }

    // Clear active state
    document.querySelectorAll('.topic-chip').forEach(c => c.classList.remove('active'));
    this.currentTopic = null;

    // Reset region
    const regionSelect = document.getElementById('region-select');
    if (regionSelect) {
      regionSelect.value = '';
      this.currentRegion = null;
    }
  }
};

// ============================================
// PRIMARY DRIVER UPDATE
// ============================================
function updatePrimaryDriver() {
  // Get alert counts from the DOM (rendered by Thymeleaf)
  const alertCategories = document.querySelectorAll('.alert-category');
  let maxCount = 0;
  let primaryDriver = null;

  alertCategories.forEach(cat => {
    const countEl = cat.querySelector('.alert-category-count');
    const nameEl = cat.querySelector('.alert-category-name');
    if (countEl && nameEl) {
      const count = parseInt(countEl.textContent, 10);
      if (count > maxCount) {
        maxCount = count;
        primaryDriver = nameEl.textContent;
      }
    }
  });

  // Update primary driver display
  if (primaryDriver && maxCount > 0) {
    const driverName = document.getElementById('driver-name');
    const driverCount = document.getElementById('driver-count');
    if (driverName) driverName.textContent = primaryDriver;
    if (driverCount) driverCount.textContent = `(${maxCount} alerts)`;
  }
}

// ============================================
// INITIALIZATION
// ============================================
document.addEventListener('DOMContentLoaded', async () => {
  // Helper: Safe module init with error boundary
  const safeInit = (name, fn) => {
    try {
      const result = fn();
      if (result instanceof Promise) {
        result.catch(err => {
          console.error(`[${name}] Init failed:`, err);
          Toast.error(`Module ${name} failed to load`);
        });
      }
    } catch (err) {
      console.error(`[${name}] Init failed:`, err);
    }
  };

  // Core UI modules (must succeed)
  safeInit('Theme', () => ThemeManager.init());
  safeInit('PWA', () => PWAManager.init());
  safeInit('Sidebar', () => SidebarManager.init());
  safeInit('Toast', () => Toast.init());

  // Feature modules (can fail gracefully)
  safeInit('Shortcuts', () => KeyboardShortcuts.init());
  safeInit('LiveIndicator', () => LiveIndicator.init());
  safeInit('AIAnalysis', () => AIAnalysisManager.init());
  safeInit('FocusAdvisor', () => FocusAdvisor.init());
  safeInit('DeepAnalysis', () => DeepAnalysisManager.init());
  safeInit('SituationDetection', () => SituationDetectionManager.init());
  safeInit('CountryDetail', () => CountryDetailManager.init());
  safeInit('CommandPalette', () => CommandPalette.init());

  // Wait for DOM to be fully ready
  await new Promise(resolve => setTimeout(resolve, 100));

  // Animations
  safeInit('Animations', () => Animations.observeElements());

  // Animate stat numbers (data-count attribute)
  document.querySelectorAll('[data-count]').forEach(el => {
    const target = parseInt(el.dataset.count, 10);
    if (!isNaN(target)) Animations.countUp(el, target);
  });

  // Animate navbar chip values on load
  document.querySelectorAll('.chip-value, .stat-value, .overview-stat-value').forEach(el => {
    const text = el.textContent.trim();
    const num = parseInt(text, 10);
    if (!isNaN(num) && num > 0) {
      el.textContent = '0';
      Animations.countUp(el, num);
    }
  });

  // Data modules
  safeInit('Charts', () => ChartManager.initMigrationChart());
  safeInit('Tabs', () => TabManager.init());
  safeInit('ClusterAlerts', () => ClusterAlertMonitor.init());

  // Update primary driver
  safeInit('PrimaryDriver', () => updatePrimaryDriver());

  // Haptics for interactive elements
  document.querySelectorAll('button, .stat-card, .glass-card').forEach(el => {
    el.addEventListener('click', () => Haptics.light());
  });

  // Offline banner (handles online/offline events + Toast notifications)
  safeInit('OfflineBanner', () => OfflineBanner.init());
});

// ============================================
// EXPORT FOR GLOBAL ACCESS
// ============================================
window.CrisisMonitor = {
  ThemeManager,
  DataManager,
  Animations,
  Haptics,
  TabManager,
  RiskScoreMonitor,
  ClimateMonitor,
  CurrencyMonitor,
  ConflictMonitor,
  IPCMonitor,
  ClusterAlertMonitor,
  AIAnalysisManager,
  DeepAnalysisManager,
  SituationDetectionManager,
  SidebarManager,
  DriverTabManager,
  IntelligenceManager,
  DailyBriefingManager,
  SituationManager,
  TopicSearch
};
