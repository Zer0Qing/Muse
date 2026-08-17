# -*- coding: utf-8 -*-
"""E1: 插入自定义主题导出/导入 4 个 key 到 7 个 locale 的 strings_settings_sub.xml。
锚点: settings_theme_custom_empty 行后。字节级插入,保留原换行。"""
import io, sys, glob

keys = {
    "values": {
        "settings_theme_custom_export": "导出主题",
        "settings_theme_custom_import": "导入主题",
        "settings_theme_custom_import_success": "主题导入成功",
        "settings_theme_custom_import_failed": "导入失败:文件格式不正确",
    },
    "values-en": {
        "settings_theme_custom_export": "Export theme",
        "settings_theme_custom_import": "Import theme",
        "settings_theme_custom_import_success": "Theme imported",
        "settings_theme_custom_import_failed": "Import failed: invalid file format",
    },
    "values-es": {
        "settings_theme_custom_export": "Exportar tema",
        "settings_theme_custom_import": "Importar tema",
        "settings_theme_custom_import_success": "Tema importado",
        "settings_theme_custom_import_failed": "Error de importación: formato no válido",
    },
    "values-ja": {
        "settings_theme_custom_export": "テーマをエクスポート",
        "settings_theme_custom_import": "テーマをインポート",
        "settings_theme_custom_import_success": "テーマをインポートしました",
        "settings_theme_custom_import_failed": "インポート失敗:ファイル形式が不正です",
    },
    "values-ko": {
        "settings_theme_custom_export": "테마 내보내기",
        "settings_theme_custom_import": "테마 가져오기",
        "settings_theme_custom_import_success": "테마를 가져왔습니다",
        "settings_theme_custom_import_failed": "가져오기 실패: 파일 형식이 올바르지 않습니다",
    },
    "values-pt-rBR": {
        "settings_theme_custom_export": "Exportar tema",
        "settings_theme_custom_import": "Importar tema",
        "settings_theme_custom_import_success": "Tema importado",
        "settings_theme_custom_import_failed": "Falha na importação: formato inválido",
    },
    "values-ru": {
        "settings_theme_custom_export": "Экспорт темы",
        "settings_theme_custom_import": "Импорт темы",
        "settings_theme_custom_import_success": "Тема импортирована",
        "settings_theme_custom_import_failed": "Ошибка импорта: неверный формат файла",
    },
}

ANCHOR = "settings_theme_custom_empty"
count = 0
for locale, kv in keys.items():
    path = f"app/src/main/res/{locale}/strings_settings_sub.xml"
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    assert ANCHOR in text, f"anchor missing in {path}"
    # 在锚点行后插入
    idx = text.index(ANCHOR)
    line_end = text.index("\n", idx) + 1
    block = "".join(f'    <string name="{k}">{v}</string>\n' for k, v in kv.items())
    new_text = text[:line_end] + block + text[line_end:]
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(new_text)
    count += 1
print(f"inserted into {count} files")
