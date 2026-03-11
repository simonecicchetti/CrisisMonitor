/**
 * Crisis Monitor - UI Test Script
 * Takes screenshots and verifies page elements
 */

const puppeteer = require('puppeteer');
const fs = require('fs');

const BASE_URL = 'http://localhost:8080';
const SCREENSHOT_DIR = './screenshots';

async function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function runTests() {
  console.log('🚀 Starting Crisis Monitor UI Tests\n');

  if (!fs.existsSync(SCREENSHOT_DIR)) {
    fs.mkdirSync(SCREENSHOT_DIR);
  }

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900 });
  await page.setCacheEnabled(false);

  const results = [];

  try {
    // Test 1: Homepage loads
    console.log('📄 Test 1: Loading homepage...');
    await page.goto(BASE_URL, { waitUntil: 'networkidle2', timeout: 30000 });
    await sleep(3000);
    await page.screenshot({ path: `${SCREENSHOT_DIR}/01-overview.png` });
    results.push({ test: 'Homepage loads', pass: true });
    console.log('   ✅ Homepage loaded\n');

    // Test 2: Click on Situations in sidebar
    console.log('🎯 Test 2: Navigating to Situations...');
    const clicked = await page.evaluate(() => {
      // Find all clickable elements
      const elements = document.querySelectorAll('a, button, div[role="button"], span');
      for (const el of elements) {
        if (el.textContent.trim() === 'Situations') {
          el.click();
          return true;
        }
      }
      return false;
    });
    await sleep(3000);
    await page.screenshot({ path: `${SCREENSHOT_DIR}/02-situations.png` });
    results.push({ test: 'Navigate to Situations', pass: clicked });
    console.log(`   ${clicked ? '✅' : '❌'} Situations tab\n`);

    // Test 3: Check situations list
    console.log('📋 Test 3: Checking situations list...');
    const situationsInfo = await page.evaluate(() => {
      const list = document.getElementById('situations-list');
      const count = document.getElementById('situations-count');
      const cards = document.querySelectorAll('.situation-card');
      return {
        haslist: !!list,
        count: count ? count.textContent : 'not found',
        cards: cards.length,
        listContent: list ? list.textContent.substring(0, 200) : 'no list'
      };
    });
    results.push({
      test: 'Situations list',
      pass: situationsInfo.cards > 0,
      details: `${situationsInfo.cards} cards, count: ${situationsInfo.count}`
    });
    console.log(`   Cards: ${situationsInfo.cards}, Count: ${situationsInfo.count}\n`);

    // Test 4: Check detection panel is hidden
    console.log('🔍 Test 4: Detection panel state...');
    const panelState = await page.evaluate(() => {
      const panel = document.getElementById('detect-situations-results');
      if (!panel) return 'not found';
      const style = window.getComputedStyle(panel);
      return style.display;
    });
    const panelHidden = panelState === 'none' || panelState === 'not found';
    results.push({ test: 'Detection panel hidden', pass: panelHidden, details: panelState });
    console.log(`   ${panelHidden ? '✅' : '❌'} Panel display: ${panelState}\n`);

    // Test 5: Click Detect button and wait
    console.log('🤖 Test 5: Testing Detect button...');
    const detectBtnExists = await page.$('#detect-situations-btn');
    if (detectBtnExists) {
      await page.click('#detect-situations-btn');
      console.log('   Clicked detect button, waiting for completion...');
      await page.screenshot({ path: `${SCREENSHOT_DIR}/03-detecting.png` });

      // Wait up to 60s for button to be re-enabled
      try {
        await page.waitForFunction(() => {
          const btn = document.getElementById('detect-situations-btn');
          return btn && !btn.disabled;
        }, { timeout: 60000 });

        await sleep(2000);
        await page.screenshot({ path: `${SCREENSHOT_DIR}/04-after-detect.png` });

        const afterDetect = await page.evaluate(() => {
          const cards = document.querySelectorAll('.situation-card');
          const count = document.getElementById('situations-count');
          return { cards: cards.length, count: count ? count.textContent : 'unknown' };
        });
        results.push({ test: 'Detect completed', pass: true, details: `${afterDetect.cards} cards` });
        console.log(`   ✅ Detection done: ${afterDetect.count}\n`);
      } catch (e) {
        results.push({ test: 'Detect completed', pass: false, details: e.message });
        console.log(`   ❌ Detection timeout\n`);
      }
    } else {
      results.push({ test: 'Detect button', pass: false, details: 'Not found' });
    }

    // Test 6: Go back to Overview and check Active Situations
    console.log('🏠 Test 6: Checking Overview Active Situations...');
    await page.evaluate(() => {
      const elements = document.querySelectorAll('a, button, div, span');
      for (const el of elements) {
        if (el.textContent.trim() === 'Overview') {
          el.click();
          return;
        }
      }
    });
    await sleep(2000);
    await page.screenshot({ path: `${SCREENSHOT_DIR}/05-overview-final.png` });

    const overviewSituations = await page.evaluate(() => {
      const container = document.getElementById('overview-top-situations');
      if (!container) return { found: false, content: 'container not found' };
      return { found: true, content: container.textContent.substring(0, 200) };
    });
    const hasOverviewSituations = overviewSituations.found &&
      !overviewSituations.content.includes('No clustered') &&
      !overviewSituations.content.includes('No active') &&
      overviewSituations.content.length > 20;
    results.push({
      test: 'Overview Active Situations',
      pass: hasOverviewSituations,
      details: overviewSituations.content.substring(0, 80)
    });
    console.log(`   ${hasOverviewSituations ? '✅' : '❌'} ${overviewSituations.content.substring(0, 60)}...\n`);

    // Test 7: API Endpoints
    console.log('🔌 Test 7: API endpoints...');
    const endpoints = [
      { url: '/api/stats', name: 'Stats' },
      { url: '/api/situations/claude-cached', name: 'Claude Cache' },
      { url: '/api/countries', name: 'Countries' }
    ];

    for (const ep of endpoints) {
      const res = await page.evaluate(async (url) => {
        try {
          const r = await fetch(url, { cache: 'no-store' });
          const data = await r.json();
          return { ok: r.ok, status: r.status, hasData: !!data };
        } catch (e) {
          return { ok: false, error: e.message };
        }
      }, ep.url);
      results.push({ test: `API: ${ep.name}`, pass: res.ok, details: `Status ${res.status}` });
      console.log(`   ${res.ok ? '✅' : '❌'} ${ep.name}: ${res.status || res.error}`);
    }

  } catch (error) {
    console.error('\n❌ Test error:', error.message);
    await page.screenshot({ path: `${SCREENSHOT_DIR}/error.png` });
    results.push({ test: 'Execution', pass: false, details: error.message });
  }

  await browser.close();

  // Summary
  console.log('\n' + '='.repeat(60));
  console.log('📊 TEST SUMMARY');
  console.log('='.repeat(60));

  const passed = results.filter(r => r.pass).length;
  const failed = results.filter(r => !r.pass).length;

  for (const r of results) {
    const details = r.details ? ` (${r.details})` : '';
    console.log(`${r.pass ? '✅' : '❌'} ${r.test}${details}`);
  }

  console.log('='.repeat(60));
  console.log(`✅ Passed: ${passed} | ❌ Failed: ${failed}`);
  console.log(`📸 Screenshots: ${SCREENSHOT_DIR}/`);

  return failed === 0;
}

runTests()
  .then(success => process.exit(success ? 0 : 1))
  .catch(err => {
    console.error('Fatal:', err);
    process.exit(1);
  });
