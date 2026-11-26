"""Reject common embedded credential assignments in tracked text files."""
import pathlib
import re
import subprocess
import sys

patterns = [
    re.compile(r"""(?im)^\s*(?:(?:private|internal|public|const|static|final)\s+)*(?:val\s+|var\s+|def\s+)?(?:influx(?:db)?_?token|api_?token|access_?token|token|credential|api_?key|password|secret)\s*(?::\s*\w+\s*)?=\s*["']([^"'\r\n]+)["']"""),
    re.compile(r"""(?im)^\s*(?:(?:private|internal|public|const)\s+)*(?:val\s+|var\s+)?(?:influxUrl|influxdb_url|influxdb_org)\s*=\s*["']([^"'\r\n]+)["']"""),
]
placeholder = re.compile(r"^(?:<[^>]+>|\$\{[^}]+\}|\$[A-Z_][A-Z_0-9]*|\*+|\[redacted\]|example|test|dummy|placeholder|your[-_].*|https://[^/]*example\.(?:org|com)(?:/.*)?)$", re.I)
extensions = {".kt", ".java", ".kts", ".py", ".properties", ".xml", ".yml", ".yaml", ".json", ".toml", ".md", ".sh", ".ps1", ".gradle", ".env", ".txt"}
issues = []
for filename in subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0"):
    if not filename:
        continue
    path = pathlib.Path(filename)
    if path.suffix.lower() not in extensions and not path.name.startswith(".env"):
        continue
    try:
        source = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        continue
    for pattern in patterns:
        for match in pattern.finditer(source):
            value = match.group(1)
            if not placeholder.fullmatch(value):
                issues.append((filename, source.count("\n", 0, match.start()) + 1))
for filename, line in sorted(set(issues)):
    print(f"Potential embedded credential: {filename}:{line}", file=sys.stderr)
if issues:
    sys.exit(1)
print("No matching embedded credential assignments in tracked text files.")
