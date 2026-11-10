import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { allPages, renderPage } from './src/worldcup/pages';

/**
 * 월드컵 경로마다 미리보기 태그가 다른 HTML 을 내보냅니다.
 *
 * 공유 미리보기를 만드는 쪽은 자바스크립트를 돌리지 않고 HTML 의 태그만 읽습니다. 그래서
 * `/worldcup` 과 결과 주소로 내보내는 파일이 첫 화면의 것과 달라야 합니다. 이유와 만드는
 * 방법은 `src/worldcup/pages.ts` 에 있습니다.
 *
 * `vercel.json` 은 고치지 않습니다. 실제로 있는 파일이 재작성 규칙보다 먼저 나가므로
 * `dist/worldcup/.../index.html` 이 있으면 그것이 나가고, 없는 경로만 첫 화면으로 갑니다.
 */
function worldcupPages(): Plugin {
  let root = process.cwd();
  let outDir = 'dist';
  return {
    name: 'wimb-worldcup-pages',
    apply: 'build',
    configResolved(config) {
      root = config.root;
      outDir = resolve(config.root, config.build.outDir);
    },
    closeBundle() {
      const themesDir = join(root, 'src/worldcup/themes');
      const themes = Object.fromEntries(
        readdirSync(themesDir)
          .filter((name) => name.endsWith('.json'))
          .map((name) => [
            name.replace(/\.json$/, ''),
            JSON.parse(readFileSync(join(themesDir, name), 'utf8')),
          ]),
      );
      const indexHtml = readFileSync(join(outDir, 'index.html'), 'utf8');
      for (const page of allPages(themes)) {
        const file = join(outDir, page.path.slice(1), 'index.html');
        mkdirSync(dirname(file), { recursive: true });
        writeFileSync(file, renderPage(indexHtml, page));
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), worldcupPages()],
  server: { host: true, port: 5173 },
});
