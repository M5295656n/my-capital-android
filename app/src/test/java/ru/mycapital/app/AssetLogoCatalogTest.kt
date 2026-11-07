package ru.mycapital.app
import org.junit.Assert.*
import org.junit.Test
class AssetLogoCatalogTest {
 @Test fun tickerLookupNormalizesInput() {
  assertEquals("logos/sber.svg", AssetLogoCatalog.assetPathFor(" sber "))
  assertEquals("logos/sber.svg", AssetLogoCatalog.assetPathFor("sberp"))
 }
 @Test fun majorIssuerLogosAreMapped() {
  listOf("GAZP","LKOH","ROSN","NVTK","YDEX","T","X5","TATN","GMKN","MOEX","MTSS","MGNT","AFLT","IRAO")
   .forEach { assertNotNull("$it should have a local logo", AssetLogoCatalog.assetPathFor(it)) }
 }
 @Test fun unknownTickerHasNoFakeLogo() { assertNull(AssetLogoCatalog.assetPathFor("UNKNOWN")) }
}
