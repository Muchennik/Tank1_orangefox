# OrangeFox для OSCAL TANK 1 (TANK_1, MT6877)

Дерево устройства и сборка OrangeFox Recovery для **OSCAL TANK 1** (Dimensity 7050, Android 15, ядро GKI 6.6).

## Как устроено

На этом телефоне нет раздела `recovery`. Рекавери живёт **отдельным фрагментом внутри `vendor_boot`** (boot header v4):

| Фрагмент `vendor_boot` | Что внутри | Что делаем |
|---|---|---|
| PLATFORM | первый этап загрузки, fstab, модули ядра | **не трогаем** (остаётся стоковым) |
| RECOVERY | рекавери | **заменяем на OrangeFox** |

Поэтому обычная загрузка Android не затрагивается: при старте системы загрузчик передаёт флаг «грузить систему», и фрагмент рекавери не запускается.

Сборка (GitHub Actions) собирает OrangeFox, а затем `tools/vendor_boot_tool.py` берёт **стоковый** `vendor_boot` (лежит в `stock/`) и заменяет в нём только фрагмент RECOVERY. Скрипт проверяет, что основной рамдиск, dtb, заголовок и cmdline остались побайтно стоковыми.

## Установка

1. Загрузчик должен быть разблокирован.
2. Скачай `OrangeFox-…-vendor_boot-….img` из [Releases](../../releases) и проверь `sha256`.
3. ```
   fastboot flash vendor_boot OrangeFox-…-vendor_boot-….img
   fastboot reboot recovery
   ```
   (`fastboot flash vendor_boot` пишет в текущий слот.)

**Не трогай `vbmeta`** (никаких `--disable-verification`): это меняет привязку ключей шифрования, и данные может понадобиться форматировать.

**Не прошивай** файлы из `debug/` и не используй встроенную «переустановку OrangeFox» поверх: в них не стоковый основной рамдиск, и система перестанет загружаться.

## Откат на сток

```
xz -d stock/vendor_boot.img.xz
fastboot flash vendor_boot stock/vendor_boot.img
```

## Версия прошивки

Собрано под стоковую прошивку `F252_DK_DK100C_8791_V0_RU_V1.2.0.1_S250917` (патч безопасности 2025-08-05). После обновления стока нужно заменить `stock/vendor_boot.img.xz` и патч безопасности в `BoardConfig.mk`, иначе может не работать расшифровка.

## Расшифровка данных

FBE v2 + шифрование метаданных. TEE — TrustKernel (KeyMint 3.0, Gatekeeper). Блобы и запуск — `recovery/root/vendor/`, `recovery/root/init.recovery.trustkernel.rc`.

## Сборка

Actions → **Build OrangeFox (OSCAL TANK 1)** → Run workflow. Готовый образ появится в Releases.

## Stock firmware packages (SP Flash Tool)

| Build | Android | Package | Link |
|---|---|---|---|
| TANK_1_S1_250917 (RU) | 15 | `F252_DK_DK100C_8791_V0_RU_V1.2.0.1_S250917.zip`, 4 973 803 258 bytes | https://mega.nz/file/KOpAiSIA#89njyNivkIxLVUw8eKlg2tA7VhagoaCWmUu5Zdh_4eE |
| TANK_1_S1_260903 (RU) | 16 | not available yet | — |

Phones updated over the air to 260903 (Android 16) must not be flashed back to 250917 (possible anti-rollback).
