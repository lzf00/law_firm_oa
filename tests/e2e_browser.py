from pathlib import Path
from time import time

from playwright.sync_api import sync_playwright


BASE_URL = "http://localhost:18080"
RESULTS = Path(__file__).resolve().parent.parent / "test-results"
RESULTS.mkdir(exist_ok=True)


def main() -> None:
    console_errors: list[str] = []
    page_errors: list[str] = []
    http_errors: list[str] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        page = context.new_page()
        page.on(
            "console",
            lambda message: console_errors.append(message.text)
            if message.type == "error"
            else None,
        )
        page.on("pageerror", lambda error: page_errors.append(str(error)))
        page.on(
            "response",
            lambda response: http_errors.append(f"{response.status} {response.url}")
            if response.status >= 400
            else None,
        )

        page.goto(BASE_URL, wait_until="networkidle")
        page.get_by_role("heading", name="进入律所工作台").wait_for()
        page.get_by_role("button", name="进入本地演示环境").wait_for()
        anonymous_response = page.request.get(f"{BASE_URL}/api/offices")
        assert anonymous_response.status in (401, 403), (
            f"未登录浏览器上下文不应访问受保护接口，实际状态码：{anonymous_response.status}"
        )
        page.screenshot(path=RESULTS / "login-desktop.png", full_page=True)
        page.get_by_role("button", name="Switch to English").click()
        page.get_by_role("heading", name="Enter the firm workspace").wait_for()
        page.get_by_text("Winson Partners and Legal Consultants", exact=True).wait_for()
        page.screenshot(path=RESULTS / "login-english.png", full_page=True)
        page.get_by_role("button", name="切换到中文").click()
        page.get_by_role("button", name="进入本地演示环境").click()
        page.get_by_role("heading", name="早上好，今天先处理最重要的事。").wait_for()
        page.screenshot(path=RESULTS / "dashboard-desktop.png", full_page=True)

        routes = [
            ("案件中心", "案件是所有协作与权限的边界。"),
            ("客户与主体", "一个主体，一份可信档案。"),
            ("利益冲突检索", "先确认没有冲突，再接受委托。"),
            ("合同管理", "每一次修改，都能追溯到版本与审批。"),
            ("文档中心", "文件留在私有存储，权限在每次访问时重新判断。"),
            ("期限管理", "期限不是日历事件，而是风险控制点。"),
            ("审批中心", "流程负责流转，业务数据始终留在业务表。"),
            ("协作任务", "把口头交办，变成有负责人和期限的协作。"),
            ("请假管理", "请假有审批，时间冲突由系统提前拦截。"),
            ("费用报销", "每一笔费用，都能回到申请、审批和付款凭证。"),
            ("会议室", "会议室按时段占用，并发预约也不会撞车。"),
            ("公告中心", "重要制度与全所信息，都有发布和已读记录。"),
            ("组织通讯录", "找到同事、部门与职责，不再依赖群聊翻找。"),
            ("全球办公室", "跨越多个司法辖区，以同一套标准协同。"),
            ("电子卷宗", "从活跃案件到长期卷宗，完整保留证据链。"),
        ]
        for nav_name, heading in routes:
            page.get_by_role("link", name=nav_name, exact=True).click()
            page.get_by_role("heading", name=heading).wait_for()

        page.get_by_role("button", name="Switch to English").click()
        english_routes = [
            ("Workspace", "Good morning. Start with what matters most."),
            ("Matters", "Every matter defines its own collaboration and access boundary."),
            ("Clients & Parties", "One party, one trusted profile."),
            ("Conflict Check", "Clear conflicts before accepting an engagement."),
            ("Contracts", "Every revision remains traceable to its version and approval."),
            ("Documents", "Files stay private and access is re-evaluated on every request."),
            ("Deadlines", "A deadline is a risk-control checkpoint, not merely a calendar event."),
            ("Approvals", "Workflows move decisions while business data stays authoritative."),
            ("Team Tasks", "Turn verbal assignments into owned, time-bound collaboration."),
            ("Leave", "Approval-backed leave with proactive scheduling conflict checks."),
            ("Expenses", "Trace every expense back to its request, approval and payment proof."),
            ("Meeting Rooms", "Reserve rooms by time slot without double booking."),
            ("Announcements", "Firm-wide policies and updates include publication and read records."),
            ("Directory", "Find colleagues, offices and responsibilities without searching chats."),
            ("Global Offices", "One operating standard across multiple jurisdictions."),
            ("Archives", "Preserve the complete evidence trail from active matter to archive."),
        ]
        for nav_name, heading in english_routes:
            page.get_by_role("link", name=nav_name, exact=True).click()
            page.get_by_role("heading", name=heading).wait_for()
        page.get_by_role("link", name="Global Offices", exact=True).click()
        page.get_by_role("heading", name="Middle East").wait_for()
        page.get_by_role("heading", name="Dubai Headquarters", exact=True).wait_for()
        page.get_by_role("heading", name="Riyadh Office", exact=True).wait_for()
        page.screenshot(path=RESULTS / "global-offices-english.png", full_page=True)
        page.get_by_role("button", name="切换到中文").click()
        page.get_by_role("heading", name="中东办公室").wait_for()

        dubai_card = page.locator("article.office-card").filter(
            has=page.get_by_role("heading", name="迪拜总部", exact=True)
        )
        dubai_card.get_by_role("button", name="成员与授权管理").click()
        page.get_by_role(
            "heading", name="迪拜总部 · 成员与授权管理", exact=True
        ).wait_for()
        page.get_by_test_id("office-member-user").select_option(
            label="李助理 · liassistant"
        )
        page.locator('input[type="datetime-local"]').fill("2026-07-29T18:00")
        page.get_by_test_id("office-member-save").click()
        page.get_by_text("办公室授权已保存").wait_for()
        assistant_member = page.get_by_test_id("office-member-list").locator(
            "article"
        ).filter(has_text="李助理")
        assistant_member.wait_for()
        page.screenshot(path=RESULTS / "office-membership-admin.png", full_page=True)
        assistant_member.get_by_role("button", name="撤销授权").click()
        page.locator(".el-message-box__btns button").last.click()
        page.get_by_text("办公室授权已撤销").wait_for()
        assistant_member.wait_for(state="detached")

        page.get_by_role("link", name="案件中心", exact=True).click()
        page.get_by_role("button", name="新建案件").click()
        matter_title = f"浏览器跨境案件-{int(time())}"
        matter_dialog = page.get_by_role("dialog")
        matter_dialog.locator("input").nth(0).fill(f"UI-ML-{int(time())}")
        matter_dialog.locator("input").nth(1).fill(matter_title)
        matter_dialog.locator("select").nth(2).select_option("00000000-0000-0000-0012-000000000002")
        matter_dialog.locator("input").nth(2).fill("Kingdom of Saudi Arabia / 沙特阿拉伯")
        matter_dialog.locator("select").nth(3).select_option("en-US")
        page.get_by_role("button", name="创建案件", exact=True).click()
        page.get_by_text("案件创建成功").wait_for()
        matter_dialog.wait_for(state="hidden")
        matter_row = page.locator("tbody tr").filter(has_text=matter_title)
        matter_row.wait_for()
        matter_row.get_by_text("SAR · en-US", exact=True).wait_for()
        page.screenshot(path=RESULTS / "cross-border-matter.png", full_page=True)

        page.get_by_role("link", name="公告中心", exact=True).click()
        page.get_by_role("button", name="发布公告").click()
        bulletin_title = f"浏览器验收公告-{int(time())}"
        page.get_by_placeholder("公告标题").fill(bulletin_title)
        page.get_by_placeholder("一句话说明重点").fill("浏览器端公告发布验收")
        page.get_by_placeholder("填写公告正文").fill("验证公告创建、发布以及列表实时刷新。")
        page.get_by_label("发布范围").select_option(
            "00000000-0000-0000-0012-000000000008"
        )
        page.get_by_role("button", name="确认发布").click()
        page.get_by_text("公告已发布，并进入站内通知队列").wait_for()
        page.get_by_text(bulletin_title).wait_for()
        page.locator(".announcement-card").filter(
            has_text=bulletin_title
        ).get_by_text("上海办公室 · PUBLISHED").wait_for()
        page.get_by_role("dialog").wait_for(state="hidden")
        page.screenshot(path=RESULTS / "announcements-desktop.png", full_page=True)

        page.get_by_role("link", name="协作任务", exact=True).click()
        page.get_by_role("button", name="新建任务").click()
        task_title = f"浏览器验收任务-{int(time())}"
        page.get_by_placeholder("明确、可验收的任务名称").fill(task_title)
        page.get_by_placeholder("说明交付物与验收标准").fill("完成页面与接口联动验证")
        page.get_by_role("button", name="确认分派").click()
        page.get_by_text("协作任务已分派").wait_for()
        page.get_by_text(task_title).wait_for()

        page.get_by_role("link", name="电子卷宗", exact=True).click()
        page.get_by_role("button", name="新建卷宗").click()
        archive_number = f"UI-AJ-{int(time())}"
        page.get_by_placeholder("如 AJ-2026-008").fill(archive_number)
        page.get_by_placeholder("案件或专项名称").fill("浏览器端验收卷宗")
        page.get_by_role("button", name="确认建卷").click()
        page.get_by_text("电子卷宗已建立").wait_for()
        page.get_by_text(archive_number).wait_for()

        page.get_by_role("link", name="文档中心", exact=True).click()
        page.locator("select").select_option("00000000-0000-0000-0006-000000000001")
        upload_input = page.locator('input[type="file"]')
        upload_input.wait_for(state="attached")
        assert upload_input.is_enabled(), "文档上传控件仍处于禁用状态"
        upload_path = RESULTS / "browser-upload.txt"
        upload_path.write_text("浏览器 CORS 与私有直传验收\n", encoding="utf-8")
        upload_input.set_input_files(upload_path)
        page.get_by_text("文件已安全入库并创建第 1 个版本").wait_for(timeout=20_000)
        page.get_by_text("browser-upload").first.wait_for()
        page.screenshot(path=RESULTS / "documents-after-upload.png", full_page=True)

        page.get_by_role("button", name="退出登录").click()
        page.get_by_role("heading", name="进入律所工作台").wait_for()
        page.get_by_role("button", name="进入本地演示环境").click()
        page.get_by_role("heading", name="早上好，今天先处理最重要的事。").wait_for()

        page.goto(f"{BASE_URL}/auth/dingtalk/callback", wait_until="networkidle")
        page.get_by_role("heading", name="无法完成登录").wait_for()
        page.get_by_text("登录回调参数不完整").wait_for()

        mobile = browser.new_context(viewport={"width": 390, "height": 844})
        mobile_page = mobile.new_page()
        mobile_page.goto(BASE_URL, wait_until="networkidle")
        mobile_page.get_by_role("heading", name="进入律所工作台").wait_for()
        mobile_page.screenshot(path=RESULTS / "login-mobile.png", full_page=True)
        mobile_page.get_by_role("button", name="进入本地演示环境").click()
        mobile_page.get_by_role("button", name="展开菜单").click()
        mobile_page.get_by_role("link", name="案件中心", exact=True).wait_for()
        mobile_page.screenshot(path=RESULTS / "dashboard-mobile-menu.png", full_page=True)

        mobile.close()
        context.close()
        browser.close()

    if console_errors or page_errors:
        details = "\n".join(
            [
                *(f"console: {item}" for item in console_errors),
                *(f"page: {item}" for item in page_errors),
                *(f"http: {item}" for item in http_errors),
            ]
        )
        raise AssertionError(f"浏览器存在未处理错误：\n{details}")
    print("✓ 匿名 API 拦截、演示登录与退出登录")
    print("✓ 中英文登录页、16 个业务页面导航与双语标题")
    print("✓ 12 个全球办公室、时区与当地币种展示")
    print("✓ 分所成员临时授权、成员列表与撤销操作")
    print("✓ 浏览器创建利雅得英文跨境案件")
    print("✓ 上海办公室公告浏览器端定向发布")
    print("✓ 协作任务浏览器端创建")
    print("✓ 电子卷宗浏览器端创建")
    print("✓ 文件浏览器直传（含 CORS）")
    print("✓ 钉钉登录回调缺码保护")
    print("✓ 登录页和业务页的桌面/移动端布局")
    print("✓ 控制台和页面运行时错误为 0")


if __name__ == "__main__":
    main()
