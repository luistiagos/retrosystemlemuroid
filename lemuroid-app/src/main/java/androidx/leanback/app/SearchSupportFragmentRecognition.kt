package androidx.leanback.app

/**
 * Cancela o início automático do reconhecimento de voz que o [SearchSupportFragment] deixou
 * agendado.
 *
 * Ao receber o `SearchResultProvider`, o fragment posta no próprio `mHandler` o
 * `mStartRecognitionRunnable`, com 300 ms de atraso, e esse runnable chama
 * `mSearchBar.startRecognition()` sem checar nulo. O `onDestroyView()` da Leanback anula o
 * `mSearchBar` mas não remove o runnable: sair da busca dentro da janela mata a main thread com
 * `NullPointerException` (leanback 1.1.0-rc01). Chamar antes do `super.onDestroyView()`.
 *
 * Mora neste pacote porque `mHandler` e `mStartRecognitionRunnable` são package-private. A única API
 * pública que remove o runnable, `setSearchQuery(String, Boolean)`, também escreve no `SearchBar` e
 * dispara `onQueryTextChange` — apagaria a busca do usuário. Reflexão não serve: o R8 renomeia os
 * campos da Leanback no release. E o `View.getHandler()` é outro handler, o do `ViewRootImpl`.
 *
 * Ver `docs/bugs/done/2026-09-24-tv-searchfragment-searchbar-npe-ondestroyview.md`.
 */
internal fun SearchSupportFragment.cancelPendingAutoStartRecognition() {
    mHandler.removeCallbacks(mStartRecognitionRunnable)
}
