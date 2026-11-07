# Журнал APK — «Мой капитал»

Этот файл — постоянный реестр релизов. Новую запись добавлять после каждой сборки; старые записи не удалять и не заменять. Всегда различать успешную CI-сборку, скачанный artifact и фактически проверенный APK.

## Последний подтверждённый релиз

### v0.3.50 — versionCode 62 — 2026-11-07
- Статус: **готовый Release APK; CI, unit tests, подпись, artifact и ресурсы логотипов проверены**.
- Ветка: `main`; commit сборки: `f9854e5e1b2ffd95ba5b2c6ede0c235e73880ab2`.
- GitHub Actions: run #579, ID `42364280277`; https://github.com/M5295656n/my-capital-android/actions/runs/42364280277
- CI: `completed / success`; job `build` — success; unit tests — **129 passed, 0 failed, 0 errors, 0 skipped** (27 XML suites); signed release APK build and uploads — success; Room instrumentation — skipped by agreed rule.
- Artifact: `my-capital-release-apk`, ID `13571665017`, ZIP size 13,375,750 bytes, ZIP SHA-256 `42360da647f1817add32b5680f1c59066bd47644bc3e102ec34a407825960c56`.
- Test artifact: `unit-test-results`, ID `13570887993`, ZIP size 20,743 bytes, ZIP SHA-256 `af11e0b98e6518922c2a361c5e1a2ce016b727ce3828dde50efe2e01541c1961`.
- APK: `MyCapital-0.3.50.apk`; local path `/mnt/data/MyCapital-0.3.50.apk`; size 13,911,928 bytes; APK SHA-256 `f94c56879ac13d19e8c021e8bc10468d049e02979515bd2124e4693c22f98f36`.
- Package: `ru.mycapital.app`; versionName `0.3.50`; versionCode `62` — confirmed in release Gradle configuration.
- APK v2 signing certificate SHA-256 fingerprint: `77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66`, matching the established signing key.
- Logo catalog: added Nornickel (GMKN), Novatek (NVTK), and Tatneft (TATN/TATNP). Catalog now maps 10 share tickers to bundled SVG/PNG assets; other tickers retain the plain-ticker fallback until verified. OFZ uses the full Russian state coat of arms.
- Offline validation: all three new files are present in the APK; the Nornickel, Tatneft and state-coat SVG files were parsed and rendered successfully; Novatek PNG dimensions are 256×256.
- Итог: versionCode increased from 61 to 62; existing signing key retained; APK is signed for update installation over the previous version.

### v0.3.49 — versionCode 61 — 2026-11-07
- Статус: **готовый Release APK; CI, unit tests, сборка, подпись, artifact и локальная проверка SVG подтверждены**.
- Ветка: `main`; commit сборки: `2b05a08389550e2db20da1c093d29db130161b85`.
- GitHub Actions: run #578, ID `42363875044`; https://github.com/M5295656n/my-capital-android/actions/runs/42363875044
- CI: `completed / success`; job `build` — success; unit tests — **129 passed, 0 failed, 0 errors, 0 skipped** (27 XML suites); signed release APK build — success; uploads — success; Room instrumentation — skipped by agreed rule.
- Artifact: `my-capital-release-apk`, ID `13570852166`, ZIP size 13,284,277 bytes, ZIP SHA-256 `39bc8053c18dc9d3b3ba8f9c766e6ddc5abff0d47cb392bf805faf30c6d1ac17`.
- Test artifact: `unit-test-results`, ID `13571445352`, ZIP size 20,813 bytes, ZIP SHA-256 `8860062047349c1b44773a34facd9c35552d3e113919821f239fbb2a8ba49500`.
- APK: `MyCapital-0.3.49.apk`; local path `/mnt/data/MyCapital-0.3.49.apk`; size 13,822,276 bytes; APK SHA-256 `3dc3617ba1b37fbd1058e51cf2cc654f8436735ab71f732db245433cb92718d1`.
- Package: `ru.mycapital.app`; versionName `0.3.49`; versionCode `61` — confirmed in the release Gradle configuration.
- APK v2 signing certificate SHA-256 fingerprint: `77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66`, matching the established signing key.
- Logo fix: removed a non-rendering Inkscape `sodipodi:nodetypes` attribute from the official multi-colour Russian coat-of-arms SVG; validated successful SVG rendering from the APK asset.
- Итог: versionCode increased from 60 to 61; existing signing key retained; APK is signed for update installation over the previous version.

### v0.3.48 — versionCode 60 — 2026-11-07
- Статус: **готовый Release APK; CI, tests, signature and artifact verified**.
- Commit: `3737075484593ef94c3cca7876db49c9072200de`; GitHub Actions run #577, ID `42363532663`; https://github.com/M5295656n/my-capital-android/actions/runs/42363532663
- CI: success; **129 unit tests passed, 0 failed, 0 errors, 0 skipped**; signed release APK and artifacts uploaded; Room instrumentation skipped.
- Artifact: `my-capital-release-apk`, ID `13570133036`; downloaded ZIP size 13,284,335 bytes; ZIP SHA-256 `d7dce4af46a29ec1706db02e6b72e1a492d8dff1111a67125e148db56209e2c0`.
- Test artifact: `unit-test-results`, ID `13570616592`; downloaded ZIP size 20,707 bytes; ZIP SHA-256 `1cf96561f1eca147cc1ce7c95f99ecde354b98cf6b48c448e6892704d31cbbb8`.
- APK: `MyCapital-0.3.48.apk`; size 13,822,304 bytes; APK SHA-256 `bf600875daf23cbde0367523c6d6ec93fb55dbd35dea88a7a63595362df583aa`.
- Version: package `ru.mycapital.app`, versionName `0.3.48`, versionCode `60`; signing certificate fingerprint matches the established key.
- Logo refresh: added verified offline SVG marks for Sber, T-Bank, Yandex and X5, plus the full Russian state coat of arms for OFZ. Other tickers remain unmapped until their artwork is verified.

## Архивные записи

### v0.3.9 — versionCode 21 — 2026-11-06
- Статус: **готовый Release APK; CI, unit tests, сборка, подпись и artifact проверены**.
- Ветка: `main`; commit сборки: `43a9e662c8f952f8a4827b2ebc58f719cc1c16f5`.
- GitHub Actions: run #434, ID `42173177041`; https://github.com/M5295656n/my-capital-android/actions/runs/42173177041
- CI: `completed / success`; job `build` — success; unit tests — **104 passed, 0 failed, 0 errors, 0 skipped** (23 XML test suites); signed release APK build — success; upload — success; Room instrumentation — skipped by agreed rule.
- Artifact: `my-capital-release-apk`, ID `13481824374`, ZIP size 13,053,578 bytes, ZIP SHA-256 `ae9d4776426982f3b05f21d070c017ce4dbe07755645f1df1f361d3cdbc59946`.
- Test artifact: `unit-test-results`, ID `13481784339`, ZIP size 17,187 bytes, ZIP SHA-256 `2b7b62ed392b2c023d216590302d389b95c14685aa16add800e6bd96f58f5494`.
- Извлечённый файл: `MyCapital-0.3.9.apk`; local path `/mnt/data/MyCapital-0.3.9.apk`; size 13,577,772 bytes; APK SHA-256 `03eeb4fb4ade66964da7ed1e14f6375dada60c4ae74bbc51a13a73dbfaa08f35`.
- Package: `ru.mycapital.app`; versionName `0.3.9`; versionCode `21` — проверены по бинарному AndroidManifest.xml.
- APK v2 signing block inspected; certificate SHA-256 fingerprint `77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66` — **побайтно совпадает с сертификатом загруженного пользователем APK v0.3.7**, поэтому сборка совместима с обновлением установленной версии, подписанной тем же ключом.
- Исправления котировок: USD/RUB и CNY/RUB добавлены в общий снимок курсов; EUR/RUB сохранён; золото запрашивается по инструменту `GLDRUB_TOM` на основной площадке MOEX `CETS`, с резервным отображением последней опубликованной цены вне торговой сессии. Подписи валютных карточек закреплены за соответствующими кодами.
- Итог: versionCode увеличен с 20 до 21; прежний ключ подписи сохранён; APK предназначен для установки поверх предыдущей версии.


### v0.3.5 — versionCode 17
- Дата: 2026-11-05
- Ветка: `main`
- Commit: `556acdc4d8844d9f5229c5d609165aac3682567c`
- GitHub Actions run ID: `42027665754`
- Workflow: https://github.com/M5295656n/my-capital-android/actions/runs/42027665754
- CI: `completed / success`; unit tests — success; release APK build — success; artifact upload — success; Room instrumentation — skipped.
- Artifact: `my-capital-release-apk`, artifact ID `13414493164`.
- ZIP size: 13,041,661 bytes.
- Artifact ZIP SHA-256: `2c32678b632022fbed8b3f33acfb5d5b15848b9c6bdd278627a30ae2e621fb69`.
- APK filename in downloaded artifact: `my-capital-release-apk.zip` (archive); extracted APK filename previously reported as `MyCapital-0.3.5.apk`, but its local file path and APK SHA-256 were not independently verified in that session.
- Package: `ru.mycapital.app`.
- Signing: release signing pipeline completed; certificate fingerprint for this specific artifact was not independently re-checked in the recorded run. Known permanent certificate from verified v0.3.3: `77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66`.
- Status: latest successful release build recorded; APK file checksum/signature identity still require direct artifact verification before making a new update-compatibility claim.

### v0.3.4 — versionCode 16
- Date: 2026-11-05
- Commit: `87d5e3e9d329f1269423c3ef7fedcb2f60f7b47d`
- GitHub Actions run ID: `42024553983`; artifact ID `13414015355`.
- Unit tests passed; Room instrumentation skipped; signed release APK reported.
- APK SHA-256: `cfa90d95419d75b97257c41846eac8c159d80c4392e7d4e8d1c5e9d443292346`.
- Status: superseded by v0.3.5.

### v0.3.3 — versionCode 15
- Date: 2026-11-05
- Commit: `86e0ca313081165db9001a89bf90907a3e644f1c`
- GitHub Actions run ID: `42011866002` (run #390).
- APK SHA-256: `5a519b2d6388fa335156855a9df8a10171f23b0297b0fe8875e7e45d06872847`.
- Signing certificate SHA-256: `77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66`.
- Status: superseded; signature fingerprint is the known permanent signing certificate reference.

## Правила ведения реестра
1. Перед изменениями и сборкой читать этот файл и сверять последний релиз.
2. После каждого CI run фиксировать версию/versionCode, commit SHA, run ID/URL, job outcomes, artifact ID/name/size/digest, APK filename/path/size/SHA-256 и certificate fingerprint. Если поле не проверено — явно писать «не проверено», не угадывать.
3. Сохранять предыдущие записи; не перезаписывать последний известный хороший APK.
4. Выдавать только Release APK, не Debug APK.
5. Передавать APK только после успешного CI, проверки package/versionCode и подписи; для установки поверх предыдущей версии сравнивать certificate fingerprint.

## v0.3.6 — versionCode 18 — 2026-11-05
- Статус: **готовый Release APK; CI и все обязательные проверки завершены успешно**.
- Ветка: `main`; commit сборки: `166c3cdc211fcd11ab70deb76f7be6f71f31589a`.
- GitHub Actions: run #415, ID `42042714624`; https://github.com/M5295656n/my-capital-android/actions/runs/42042714624
- CI: `completed / success`; job `build` — success; unit tests — **95 passed, 0 failed, 0 errors**; signed release APK build — success; upload — success; Room instrumentation — skipped by agreed rule.
- Предыдущий run #414 (ID `42041978309`, code commit `d78442e0ef441a71a35d209d22a8e62c5d4e23e7`) первоначально выявил 3 теста, падавших из-за Android `org.json.JSONObject` stub в JVM. Парсер переведён на безопасный разбор строки JSON; повторные 95 тестов прошли успешно. Run #415 повторно проверил итоговый main и собрал релиз после настройки пропуска CI для docs-only commits.
- Artifact: `my-capital-release-apk`, ID `13421703266`, ZIP size 13,042,462 bytes, ZIP SHA-256 `bc1963cad8e9f77c16f05ac2361bad616a5b369d79be2d91dab2d50386203f9f`.
- Извлечённый файл: `MyCapital-0.3.6.apk`; local path `/mnt/data/MyCapital-0.3.6.apk`; size 13,577,772 bytes; APK SHA-256 `03159718f40890fee288cf030a4b3521e5b29515c9166740a8e55cf8bf9c5cbb`.
- Package: `ru.mycapital.app`; versionName `0.3.6`; versionCode `18` (source configuration checked).
- APK v2 signing block inspected; certificate SHA-256 fingerprint `77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66` — **совпадает с постоянным ключом подтверждённого предыдущего релиза**.
- Итог: релиз подписан прежним сертификатом, versionCode увеличен с 17 до 18; предназначен для установки поверх предыдущей версии.


## v0.3.7 — versionCode 19 — 2026-11-05
- Статус: **готовый Release APK; CI, unit tests, сборка, подпись и artifact проверены**.
- Ветка: main; commit сборки: 0a8dd621c21e109461c19af8253d6747c8cbc24e.
- GitHub Actions: run #419, ID 42050758470; https://github.com/M5295656n/my-capital-android/actions/runs/42050758470
- CI: completed / success; job build — success; unit tests — **100 passed, 0 failed, 0 errors, 0 skipped** (23 XML test suites); signed release APK build — success; artifact upload — success; Room instrumentation — skipped by agreed rule.
- Artifact: my-capital-release-apk, ID 13426956352, ZIP size 13,051,238 bytes, ZIP SHA-256 6106ca40ffcb7ef6deb4b627e75c025ba6e263586789d2cf626c45f3510e345f.
- Test artifact: unit-test-results, ID 13426941256, ZIP size 16,997 bytes, ZIP SHA-256 03dfeaf6c12cf4512031525ab785587e18e570d7828fda34ef60d1374be4454e.
- Извлечённый файл: MyCapital-0.3.7.apk; local path /mnt/data/MyCapital-0.3.7.apk; size 13,577,772 bytes; APK SHA-256 ab40ec1ee6e47866d613db91129ff638f08251ae9bd6d972b52901cf11f002a2.
- Package: ru.mycapital.app; versionName 0.3.7; versionCode 19 — проверены по бинарному AndroidManifest.xml и конфигурации Gradle.
- APK v2 signing block inspected; certificate SHA-256 fingerprint 77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66 — совпадает с постоянным сертификатом предыдущих подтверждённых релизов.
- Итог: versionCode увеличен с 18 до 19, подпись прежняя; APK предназначен для установки поверх предыдущей версии.

## v0.3.8 — versionCode 20 — 2026-11-05
- Статус: **готовый Release APK; CI, unit tests, сборка, подпись и artifact проверены**.
- Ветка: main; commit сборки: 7111e9846bb019d8fd3feb1019340b422a007fb8.
- GitHub Actions: run #429, ID 42071749653; https://github.com/M5295656n/my-capital-android/actions/runs/42071749653
- CI: completed / success; job build — success; unit tests — **102 passed, 0 failed, 0 errors, 0 skipped** (23 XML test suites); signed release APK build — success; artifact upload — success; Room instrumentation — skipped by agreed rule.
- Artifact: my-capital-release-apk, ID 13436827218, ZIP size 13,053,301 bytes, ZIP SHA-256 9577b5f73d4f2c7a8c60d06e80eee947a9c7833b872d9adb1fb181001bb44047.
- Test artifact: unit-test-results, ID 13437022110, ZIP size 17,057 bytes, ZIP SHA-256 e20ba35fa91cb6e922710ec6d62196423ed60b2033c2938f1ee3bc40363e291f.
- Извлечённый файл: MyCapital-0.3.8.apk; local path /mnt/data/MyCapital-0.3.8.apk; size 13,577,772 bytes; APK SHA-256 da5803b4777263b5311193f214f5a7e8817597a283d30071eb980d16f5553930.
- Package: ru.mycapital.app; versionName 0.3.8; versionCode 20 — проверены по бинарному AndroidManifest.xml.
- APK v2 signing block inspected; certificate SHA-256 fingerprint 77FE78460534AC46DA9A42B03435DEF8CC5D2D6D3CC67C7D3C6C8FD35008FD66 — совпадает с постоянным сертификатом предыдущих подтверждённых релизов.
- Основные исправления: немедленная синхронизация дивидендов при загрузке/изменении состава портфеля вместо ожидания первого 24-часового периодического запуска; диагностика количества записей источника и совпадений с тикерами портфеля на экране «Выплаты»; поддержка CSV с разделителями comma/semicolon/tab; дивидендные прогнозы с отсечкой дальше 90 дней больше не скрываются вместе с дальними купонами; разрешено подтверждение фактического получения для объявленных/рекомендованных/прогнозных дивидендов.
- Итог: versionCode увеличен с 19 до 20, подпись прежняя; APK предназначен для установки поверх предыдущей версии.
