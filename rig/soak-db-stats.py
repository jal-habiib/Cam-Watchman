import sqlite3
import sys

connection = sqlite3.connect(sys.argv[1], timeout=5)
rows = connection.execute(
    "select camera_id,status,count(*),coalesce(sum(size_bytes),0) "
    "from recordings group by camera_id,status order by camera_id,status"
).fetchall()
print(";".join(":".join(map(str, row)) for row in rows))
