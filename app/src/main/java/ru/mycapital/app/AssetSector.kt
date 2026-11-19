package ru.mycapital.app

internal fun sectorForTicker(ticker: String): String = when (ticker.uppercase()) {
    "SBER", "SBERP", "T", "VTBR", "MOEX", "BSPB", "CBOM", "DOMRF" -> "Финансы"
    "LKOH", "ROSN", "TATN", "GAZP", "NVTK", "SNGS", "SNGSP", "BANE", "TRNFP" -> "Нефть и газ"
    "OZON", "MVID", "FIXP", "X5", "MGNT", "LENT" -> "Потребительский сектор"
    "YDEX" -> "Информационные технологии"
    "MDMG", "GEMC" -> "Здравоохранение"
    "OZPH" -> "Фармацевтика"
    "GMKN", "NLMK", "CHMF", "MAGN", "RUAL", "ALRS" -> "Металлы и добыча"
    "MTSS", "RTKM", "VEON" -> "Телеком"
    "AFLT", "FLOT" -> "Транспорт"
    "PIKK", "SMLT", "LSRG" -> "Недвижимость"
    "IRAO", "HYDR", "FEES", "UPRO", "LSNG", "LSNGP" -> "Электроэнергетика"
    else -> "Прочие акции"
}
