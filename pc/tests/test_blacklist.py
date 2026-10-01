"""黑名单写回单测：逐行改写 TOML，注释与其它段落必须原样保留。"""

from __future__ import annotations

import tomllib

from vnotif.config import save_blacklist

COMMENTED = """\
# 顶层注释：别删我
[server]
host = "0.0.0.0"
port = 8765
# 手机端要填同一个 token
token = "abc"

[filter]
# 黑名单注释：也保留我
apps = ["kscreen", "fcitx5"]
# 正文正则注释
body_regex = ["^音量"]
"""

MULTILINE = """\
[filter]
apps = [
    "oldapp1",
    "oldapp2",
]
body_regex = []
"""

NO_FILTER = """\
[server]
host = "1.2.3.4"
port = 9000
# server 段注释
token = "xyz"
"""


def test_single_line_array_and_comments_kept(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(COMMENTED, encoding="utf-8")

    save_blacklist(["Element", "discord.desktop"], path)

    data = tomllib.loads(path.read_text(encoding="utf-8"))
    assert data["filter"]["apps"] == ["Element", "discord.desktop"]
    # 其它段落没被改动
    assert data["server"]["token"] == "abc"
    assert data["filter"]["body_regex"] == ["^音量"]

    text = path.read_text(encoding="utf-8")
    assert "# 顶层注释：别删我" in text
    assert "# 黑名单注释：也保留我" in text
    assert "# 正文正则注释" in text


def test_multiline_array_replaced_wholesale(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(MULTILINE, encoding="utf-8")

    save_blacklist(["newapp"], path)

    data = tomllib.loads(path.read_text(encoding="utf-8"))
    assert data["filter"]["apps"] == ["newapp"]
    assert data["filter"]["body_regex"] == []

    text = path.read_text(encoding="utf-8")
    assert "oldapp1" not in text
    assert "oldapp2" not in text
    # 旧数组的孤立闭合行不能残留：整份文件只剩新数组那一行 ]
    assert sum(1 for line in text.splitlines() if line.strip() == "]") == 1


def test_missing_filter_section_appended(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(NO_FILTER, encoding="utf-8")

    save_blacklist(["Foo"], path)

    data = tomllib.loads(path.read_text(encoding="utf-8"))
    assert data["filter"]["apps"] == ["Foo"]
    # 原有 [server] 内容不丢
    assert data["server"]["host"] == "1.2.3.4"
    assert data["server"]["port"] == 9000
    assert data["server"]["token"] == "xyz"
    assert "# server 段注释" in path.read_text(encoding="utf-8")


def test_dedup_case_and_desktop_suffix(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(COMMENTED, encoding="utf-8")

    save_blacklist(["Foo", "foo.desktop", "FOO", "bar"], path)

    data = tomllib.loads(path.read_text(encoding="utf-8"))
    assert data["filter"]["apps"] == ["Foo", "bar"]


def test_empty_list_writes_empty_array(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(COMMENTED, encoding="utf-8")

    save_blacklist([], path)

    text = path.read_text(encoding="utf-8")
    # 注意：给定的逐行拼接实现把空列表写成多行 "apps = [\n]"（语义等价，TOML 解析为 []），
    # 而不是规格描述的 "apps = []"。这里按实现的实际输出断言。
    assert "apps = [\n]" in text
    assert tomllib.loads(text)["filter"]["apps"] == []


def test_creates_config_when_missing(tmp_path):
    path = tmp_path / "sub" / "config.toml"

    save_blacklist(["Foo"], path)

    assert path.exists()
    assert tomllib.loads(path.read_text(encoding="utf-8"))["filter"]["apps"] == ["Foo"]
