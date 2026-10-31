from datetime import datetime
from sqlalchemy import Column, Integer, String, Boolean, ForeignKey, Text, UniqueConstraint
from sqlalchemy.orm import declarative_base

Base = declarative_base()

class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, index=True)
    tg_id = Column(Integer, unique=True, nullable=False, index=True)
    username = Column(String, nullable=True)
    full_name = Column(String, nullable=False)
    role = Column(String, default="user")          # "admin" или "user"
    status = Column(String, default="pending")     # "pending", "approved", "blocked"
    api_token = Column(String, unique=True, index=True, nullable=True)
    pair_code = Column(String, nullable=True)

class Lesson(Base):
    __tablename__ = "lessons"
    id = Column(Integer, primary_key=True, index=True)
    date_str = Column(String, nullable=False, index=True)     # "26.10.2026"
    sort_key = Column(Integer, nullable=False, index=True)    # 20261026
    day_label = Column(String, nullable=False)                # "понедельник, 26 октября 2026 г."
    start_time = Column(String, nullable=False)               # "08:05"
    end_time = Column(String, nullable=False)                 # "09:35"
    lesson_type = Column(String, nullable=False)              # "Л", "П", "ЛР"
    subject = Column(String, nullable=False, index=True)
    teacher = Column(String, default="")
    groups = Column(String, default="")
    room = Column(String, default="")
    is_custom_override = Column(Boolean, default=False)

    __table_args__ = (
        UniqueConstraint("date_str", "start_time", "subject", name="uq_lesson_slot"),
    )

class Homework(Base):
    __tablename__ = "homework"
    id = Column(Integer, primary_key=True, index=True)
    date_str = Column(String, nullable=False, index=True)
    subject = Column(String, nullable=False)
    description = Column(Text, nullable=False)
    created_by = Column(Integer, ForeignKey("users.tg_id"))
    created_at = Column(String, default=lambda: datetime.now().strftime("%d.%m.%Y %H:%M"))

class Reminder(Base):
    __tablename__ = "reminders"
    id = Column(Integer, primary_key=True, index=True)
    user_tg_id = Column(Integer, ForeignKey("users.tg_id"), nullable=False, index=True)
    title = Column(String, nullable=False)
    remind_date = Column(String, nullable=False, index=True)
    remind_time = Column(String, nullable=False, index=True)
    lesson_id = Column(Integer, ForeignKey("lessons.id"), nullable=True)
    homework_id = Column(Integer, ForeignKey("homework.id"), nullable=True)
    is_sent = Column(Boolean, default=False, index=True)
