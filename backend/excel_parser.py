import re
from openpyxl import load_workbook

RU_MONTHS = {
    "января": "01", "февраля": "02", "марта": "03", "апреля": "04",
    "мая": "05", "июня": "06", "июля": "07", "августа": "08",
    "сентября": "09", "октября": "10", "ноября": "11", "декабря": "12"
}

def parse_ru_date_dmy(raw_date: str) -> tuple[str, int]:
    match = re.search(r"(\d{1,2})\s+([а-яА-ЯёЁ]+)\s+(\d{4})", raw_date.lower())
    if not match:
        clean = raw_date.strip()
        return clean, 0
    day, month_str, year = match.groups()
    month = RU_MONTHS.get(month_str, "01")
    day_padded = f"{int(day):02d}"
    date_dmy = f"{day_padded}.{month}.{year}"
    sort_key = int(f"{year}{month}{day_padded}")
    return date_dmy, sort_key

def parse_schedule_xlsx(file_path: str) -> list[dict]:
    wb = load_workbook(file_path, data_only=True)
    sheet = wb["Расписание"] if "Расписание" in wb.sheetnames else wb.active
    dedup_map = {}

    for row in sheet.iter_rows(min_row=3, values_only=True):
        if not row or not row[0] or not row[1] or not row[4]:
            continue
        day_label = str(row[0]).strip()
        if day_label.lower() == "дата":
            continue

        date_str, sort_key = parse_ru_date_dmy(day_label)
        start_time = str(row[1]).strip()
        end_time = str(row[2] or "").strip()
        lesson_type = str(row[3] or "").strip()
        subject = str(row[4]).strip()
        teacher = str(row[5] or "").strip() if row[5] else ""
        groups = str(row[6] or "").strip() if row[6] else ""
        room = re.sub(r"\s+", " ", str(row[7]).strip()) if row[7] else ""

        key = (date_str, start_time, subject)
        if key in dedup_map:
            if not dedup_map[key]["room"] and room:
                dedup_map[key]["room"] = room
            if not dedup_map[key]["teacher"] and teacher:
                dedup_map[key]["teacher"] = teacher
        else:
            dedup_map[key] = {
                "date_str": date_str,
                "sort_key": sort_key,
                "day_label": day_label,
                "start_time": start_time,
                "end_time": end_time,
                "lesson_type": lesson_type,
                "subject": subject,
                "teacher": teacher,
                "groups": groups,
                "room": room,
            }
    return sorted(dedup_map.values(), key=lambda x: (x["sort_key"], x["start_time"]))
