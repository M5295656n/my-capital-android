# Журнал APK — «Мой капитал»

Этот файл — постоянный реестр релизов. Новую запись добавлять после каждой сборки; старые записи не удалять и не заменять. Всегда различать успешную CI-сборку, скачанный artifact и фактически проверенный APK.

## Последний подтверждённый релиз до текущей задачи

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
