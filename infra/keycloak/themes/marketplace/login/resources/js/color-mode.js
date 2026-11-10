// Segue o tema claro ou escuro do sistema, como o modo automático do painel (ColorModeService do CoreUI).
(() => {
  const dark = window.matchMedia('(prefers-color-scheme: dark)');
  const apply = () =>
    document.documentElement.setAttribute('data-coreui-theme', dark.matches ? 'dark' : 'light');
  apply();
  dark.addEventListener('change', apply);
})();
