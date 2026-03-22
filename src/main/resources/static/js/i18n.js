/**
 * Notamy News i18n System
 * Loads translations from /i18n/{lang}.json and applies to all elements with data-i18n attribute.
 * Also provides t() function for dynamic JS strings.
 */
const I18n = {
  currentLang: 'en',
  strings: {},
  loaded: false,

  async init() {
    this.currentLang = localStorage.getItem('notamy-lang') || 'en';
    await this.load(this.currentLang);
  },

  async load(lang) {
    try {
      const response = await fetch(`/i18n/${lang}.json`);
      if (!response.ok) throw new Error('HTTP ' + response.status);
      this.strings = await response.json();
      this.currentLang = lang;
      this.loaded = true;
      this.apply();
      console.log('[i18n] Loaded:', lang, Object.keys(this.strings).length, 'sections');
    } catch (error) {
      console.warn('[i18n] Failed to load', lang, '- falling back to en');
      if (lang !== 'en') {
        await this.load('en');
      }
    }
  },

  /**
   * Get a translated string by dot-path: t('nav.overview') → 'Panoramica'
   */
  t(path, fallback) {
    const parts = path.split('.');
    let val = this.strings;
    for (const p of parts) {
      if (val && typeof val === 'object' && p in val) {
        val = val[p];
      } else {
        return fallback || path;
      }
    }
    return typeof val === 'string' ? val : (fallback || path);
  },

  /**
   * Apply translations to all elements with data-i18n attribute.
   * data-i18n="nav.overview" → sets textContent
   * data-i18n-placeholder="community.addComment" → sets placeholder
   * data-i18n-title="..." → sets title attribute
   */
  apply() {
    // Text content
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const key = el.getAttribute('data-i18n');
      const val = this.t(key);
      if (val !== key) el.textContent = val;
    });

    // Placeholders
    document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
      const key = el.getAttribute('data-i18n-placeholder');
      const val = this.t(key);
      if (val !== key) el.placeholder = val;
    });

    // Title attributes
    document.querySelectorAll('[data-i18n-title]').forEach(el => {
      const key = el.getAttribute('data-i18n-title');
      const val = this.t(key);
      if (val !== key) el.title = val;
    });

    // Sidebar labels (special handling — nested inside buttons)
    document.querySelectorAll('.sidebar-item').forEach(btn => {
      const section = btn.dataset.section;
      const label = btn.querySelector('.sidebar-label');
      if (!label) return;
      const key = section === 'early-warning' ? 'earlyWarning' : section === 'news-feed' ? 'newsFeed' : section;
      const val = this.t('nav.' + key);
      if (val !== 'nav.' + key) label.textContent = val;
    });
  },

  /**
   * Switch language — reload translations and re-apply.
   */
  async switchTo(lang) {
    if (lang === this.currentLang && this.loaded) return;
    localStorage.setItem('notamy-lang', lang);
    window._platformLang = lang;
    await this.load(lang);
  }
};
