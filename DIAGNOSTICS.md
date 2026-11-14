# Terrain Diffusion: диагностика Phase 3

Версия: 2.12.10, Fabric 1.21.11, Java 21. Это расширение существующего `PerformanceDiagnostics`, а не отдельный scheduler или сервис телеметрии.

## Где смотреть

1. В `logs/latest.log` ищите `[TerrainDiffusion][TD-`. Сопоставляйте сообщения по `RequestId`, session, dimension и координатам.
2. Для FAILED pipeline/inference/hydrology/worker смотрите `logs/terrain-diffusion/td-critical-*.json`. Файл создаётся существующим потоком `terrain-performance-diagnostics`, независимо от включения подробного performance trace.
3. Для задержек используйте существующие команды `/td-perf`: сначала их справку, затем snapshot/статус. Файлы `logs/terrain-diffusion-performance.jsonl` содержат существующую временную шкалу и runtime counters. Совпадение работы с пиком кадра само по себе не доказывает причину.

Critical JSON включает стабильный `error_code`, subsystem, stage/request context, provider, версии доступных интеграций, Java/platform, последовательность событий, inference/admission counters, ONNX timings/input buffers и счётчики RAM/disk hits, вычисления/сохранения тайлов, FULL chunks. Снимок очередей делается во время записи отчёта, а не атомарно со сбоем. Неизвестные значения остаются `unknown`.

На обычном клиенте dimension привязывается к сессии мира. Raw/inference сохраняют request context между потоками; гидрология привязывает известные cell/block coordinates и ticket своей сессии. Некоторые ошибки ранней загрузки моделей возникают до появления мира: session/dimension/coordinates тогда неизвестны.

## Как включить подробности

Для короткого диагностического запуска добавьте **JVM argument**, не Minecraft command:

```text
-Dterrain.diffusion.diagnostics.debug=true
```

Он включает дополнительные полные проверки конечности выходных массивов. Обычно проверяются размер выхода, до 64 значений каждого native output, а также каждая высота при уже необходимом преобразовании heightmap. Полный scan больших тензоров постоянно не выполняется. Отображение DEBUG-сообщений в `latest.log` также зависит от уровня существующего Log4j/SLF4J appender; свойство не переписывает его конфигурацию.

Для проверки старого ROI пути отдельно существует:

```text
-Dterrain.diffusion.roi.disable=true
```

Это только CPU upsampling fallback. Оно не меняет модель, профиль мира или формат кэша. Эти параметры автоматически не устанавливаются и в игровую конфигурацию не записываются.

## Значение результата

| Result | Значение |
|---|---|
| FAILED | Результат не получен; исходное исключение передано вызывающему коду. Пустой чанк/случайный рельеф не подставляются. |
| DEGRADED | Ограниченная функция недоступна либо ещё требуется пересчёт/восстановление. Это не обещание успешной генерации. |
| CANCELLED | Обычная отмена, прерывание или устаревший world ticket. По умолчанию не ERROR. |
| RECOVERED | Формат поддерживает подтверждённое восстановление. Предстоящий пересчёт никогда не объявляется RECOVERED заранее. |

При нескольких сообщениях одного запроса сначала проверяйте самое внутреннее исключение. Один сбой может пройти через INF → SCH → WGN: это цепочка распространения, а не три независимые неисправности.

## Каталог стабильных кодов

| Код | Причина / граница обнаружения | Реакция |
|---|---|---|
| TD-INF-001 | Некорректная таблица входов, повтор имени, shape/data mismatch, overflow размера | FAILED, native Run не запускается |
| TD-INF-002 | Ошибка native inference на не-CPU provider | FAILED, original cause сохраняется |
| TD-INF-003 | Нет tensor output либо неверна ожидаемая длина coarse/latent/decoder | FAILED |
| TD-INF-004 | NaN/Infinity в проверяемом critical output/heightmap | FAILED; не превращается в нулевую высоту |
| TD-INF-005 | Явно затребованный GPU provider недоступен | FAILED; настройка пользователя не меняется |
| TD-INF-006 | Ошибка CPU inference, в том числе CPU-пути восстановления | FAILED |
| TD-MOD-001 | Нет необходимого model file / asset preparation завершилась I/O ошибкой | FAILED с исходным exception |
| TD-MOD-002 | Ошибка проверки/инициализации модели или фоновой загрузки | FAILED |
| TD-MOD-003 | Отвергнут optimized model cache / не удалась оптимизация | DEGRADED; существующий ограниченный путь использует source model/rebuild optimized cache |
| TD-CAC-001 | CRC сохранённого terrain entry не совпал | DEGRADED; только запись игнорируется, нужен пересчёт |
| TD-CAC-002 | Не поддерживается версия terrain entry | То же; остальные записи сохраняются |
| TD-CAC-003 | EOF/обрезанный header или body | То же |
| TD-CAC-004 | Неверная identity/структура/данные terrain entry, включая non-finite climate | То же |
| TD-CAC-005 | Ошибка общей single-flight tile computation | FAILED, исходная причина пробрасывается |
| TD-CAC-006 | Отказ admission квоты / accounting | FAILED; существующая отмена/освобождение reservation сохраняются |
| TD-HYD-001 | Ошибка water planning/foreground water dependency | FAILED; неверная вода не публикуется |
| TD-HYD-002 | Нет raw terrain, обязательного для замороженной water plan | FAILED; не подменяется новым случайным планом |
| TD-HYD-003 | Некорректный/чужой frozen water unit/tile, grid/identity/version mismatch | FAILED; original exception/cause сохраняется |
| TD-HYD-004 | Ошибка записи frozen water plan | FAILED |
| TD-WGN-001 | Не выполнен обязательный terrain-region request | FAILED; cause/request сохранены |
| TD-WGN-002 | Несогласованные dimensions/channel lengths при сборке heightmap | FAILED до публикации |
| TD-SCH-001 | Сбой callable/background future | FAILED для inference; DEGRADED для необязательного prefetch |
| TD-SCH-002 | Uncaught failure inference/diagnostics worker | FAILED; стандартный лог остаётся основным свидетельством |
| TD-SCH-003 | Admission ждёт более 30 секунд | WARN/DEGRADED; обязательная задача не выбрасывается |
| TD-SCH-004 | Foreground queue wait >30 с либо materialized water unit удерживается >60 с | WARN/DEGRADED; это симптом, не диагноз deadlock |
| TD-IO-001 | Terrain entry не прочитан по причине I/O, отличной от отсутствия файла | DEGRADED, нужен пересчёт; missing file остаётся тихим cache miss |
| TD-IO-002 | Terrain entry не записан | FAILED; исключение пробрасывается |
| TD-IO-003 | Не удалось сохранить performance/critical report | DEGRADED; исходная ошибка остаётся в стандартном логе |
| TD-VOX-001 | Нет необязательного Voxy | INFO; обычная генерация доступна |
| TD-VOX-002 | Не поддерживается версия Voxy/VoxyWorldGen | DEGRADED; неподдерживаемый bridge не запускается |
| TD-VOX-003 | Reflection/API/initialization mismatch bridge | DEGRADED; отключается соответствующий optional bridge |
| TD-VOX-004 | Не удалось преобразование/публикация LOD | DEGRADED; обычные chunks не подменяются |
| TD-VOX-005 | Ошибка асинхронной подготовки LOD | DEGRADED либо CANCELLED при уходе из мира |
| TD-VOX-006 | Экспериментальный coarse-предпросмотр остановлен отказом (Phase 4, флаг off по умолчанию) | DEGRADED; отключается только preview, экспорт и генерация не меняются |
| TD-VOX-007 | Затопление очереди/конфликт владения экспериментального coarse | DEGRADED; ограниченные очереди, статистика в /td-voxy status |
| TD-MEM-001 | GPU memory pressure, распознанная существующим механизмом | DEGRADED; существующий bounded session-cache recovery |
| TD-LFC-001 | Отмена/retired session | CANCELLED, подробности только DEBUG |
| TD-LFC-002 | Некорректное использование/закрытие ONNX session | FAILED либо DEGRADED при cleanup |

Нет нового универсального CPU fallback. Сохраняются существующие provider selection, GPU memory recovery и ограничения single owner; `inference.device=gpu` не переписывается на CPU после произвольной ошибки. Нет VoxySeedGen bridge в этой фазе.

## Ограничения ресурсов и приватность

- Не больше 64 недавних событий и 4 ожидающих critical reports в памяти.
- Повтор одного code/result логируется не чаще раза в 15 секунд; следующий разрешённый лог содержит `SuppressedSincePrevious`. WARN не подавляет последующий FAILED того же кода.
- До 10 собственных `td-critical-*.json`, размер каждого до 128 KiB. Удаление при ротации касается только файлов с точным собственным шаблоном имени. Чужие файлы не затрагиваются.
- Построение stack trace ограничено примерно 24 тысячами символов и 32 исключениями в цепочке; превышение явно помечается. Полные tensors/модели/приватные файлы не сохраняются.
- В critical JSON обезличиваются распространённые абсолютные private paths, credential labels/Bearer и URL credentials. Оригинальный Throwable остаётся у стандартного Minecraft logger для сохранения причины: в полном `latest.log` по-прежнему могут встречаться исходные пути и сообщения библиотек.
- Дисковые записи выполняет существующий diagnostics worker. Обычные сообщения идут через стандартный logger Minecraft. Новых worker pools, сетевой отправки, тяжёлого per-block логирования нет.
- Сбой writer не заменяет исходный exception. Fatal resource exhaustion может помешать любому логированию; гарантировать файл при OOM/выключении процесса невозможно.

## Пример из искусственной проверки

Это **test fixture**, не сбой пользовательского мира:

```text
[TerrainDiffusion][TD-INF-002] Subsystem=ONNX_INFERENCE Stage=SYNTHETIC_INFERENCE
Dimension=test:dimension Session=opaque-test-session Coordinates=tile=(-1,2)
RequestId=td-fixture Thread=main Provider=unknown
Failure=Native inference failed Cause=IOException: Simulated inference failure <redacted>
Recovery=Propagated in isolated fixture; no terrain published Result=FAILED
```

Полные тестовые сообщения: [diagnostics-fixture.log](<C:/Users/221il/Downloads/European Wildrness/research/optimization-phase3-2026-11-06/diagnostics-fixture.log>). Примеры JSON находятся в соседней папке `example-critical-reports`; секрет fixture в JSON и строках сообщений отсутствует. На 100000 повторов выведено одно агрегированное продолжение, ring остался 64; отдельный FAILED после WARN не подавляется.

## Разбор проблемы

Сохраните первый relevant code, RequestId, координаты, provider и root exception. Сверьте `Result` и `Recovery`: фраза «recomputation required» ещё не означает удачный пересчёт. При долгом ожидании сравните queue age/high-watermarks, ONNX run/session creation и water timings. При cache issue проверяйте конкретный entry, не удаляйте весь кэш. При optional API mismatch смотрите версию bridge; не меняйте terrain algorithm. Для сравнения производительности используйте одинаковые seed, маршрут, cache state, JVM/provider и отдельную копию мира.
