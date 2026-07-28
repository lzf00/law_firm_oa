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
        matter_number = f"UI-ML-{int(time())}"
        matter_dialog = page.get_by_role("dialog")
        matter_dialog.locator("input").nth(0).fill(matter_number)
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

        page.get_by_role("link", name="客户与主体", exact=True).click()
        page.get_by_role("button", name="新建主体", exact=True).click()
        party_name = f"浏览器验收客户-{int(time())}"
        party_dialog = page.get_by_role("dialog")
        party_dialog.locator("input").nth(1).fill(party_name)
        party_dialog.locator("input").nth(2).fill(f"浏览器别名-{int(time())}")
        page.get_by_role("button", name="保存主体", exact=True).click()
        page.get_by_text("主体已保存").wait_for()
        party_dialog.wait_for(state="hidden")
        party_card = page.locator("article.party-card").filter(has_text=party_name)
        party_card.wait_for()
        party_card.get_by_role("button", name="转为客户").click()
        client_dialog = page.get_by_role("dialog")
        client_number = f"UI-CL-{int(time())}"
        client_dialog.locator("input").nth(0).fill(client_number)
        page.get_by_role("button", name="保存客户", exact=True).click()
        page.get_by_text("客户档案已保存").wait_for()
        client_dialog.wait_for(state="hidden")
        page.get_by_text(client_number, exact=True).wait_for()

        page.get_by_role("link", name="合同管理", exact=True).click()
        page.get_by_role("button", name="新建合同", exact=True).click()
        contract_dialog = page.get_by_role("dialog")
        contract_number = f"UI-CT-{int(time())}"
        contract_title = f"浏览器验收合同-{int(time())}"
        contract_dialog.locator("input").nth(0).fill(contract_number)
        contract_dialog.locator("input").nth(2).fill(contract_title)
        contract_dialog.locator("select").nth(0).select_option(label=f"{client_number} · {party_name}")
        contract_dialog.locator("select").nth(2).select_option(label=f"{matter_number} · {matter_title}")
        page.get_by_role("button", name="保存合同", exact=True).click()
        page.get_by_text("合同已保存").wait_for()
        contract_dialog.wait_for(state="hidden")
        page.get_by_text(contract_title, exact=True).wait_for()
        contract_row = page.locator("tbody tr").filter(has_text=contract_title)
        contract_row.get_by_role("button", name="送审", exact=True).click()
        page.get_by_text("合同评审已发起").wait_for()
        contracts_response = page.request.get(
            f"{BASE_URL}/api/contracts",
            headers={"X-Dev-User": "admin"},
        )
        assert contracts_response.ok, "无法读取浏览器新建合同"
        contract_id = next(
            item["id"] for item in contracts_response.json()
            if item["title"] == contract_title
        )

        page.get_by_role("link", name="审批中心", exact=True).click()
        approval_task = page.locator("article.task-row").filter(
            has_text=f"CONTRACT:{contract_id}"
        )
        approval_task.wait_for()
        approval_task.get_by_role("button", name="驳回", exact=True).click()
        approval_dialog = page.get_by_role("dialog")
        approval_dialog.get_by_role("button", name="驳回审批", exact=True).click()
        page.get_by_text("驳回必须填写原因").wait_for()
        page.keyboard.press("Escape")
        approval_dialog.wait_for(state="hidden")
        approval_task.get_by_role("button", name="转交", exact=True).click()
        transfer_dialog = page.get_by_role("dialog")
        transfer_select = page.get_by_test_id("transfer-target")
        page.wait_for_function(
            "() => document.querySelector('[data-testid=\"transfer-target\"]')?.options.length > 1"
        )
        assert transfer_select.locator("option").count() > 1, (
            "审批转交未返回符合业务访问范围的候选人"
        )
        page.keyboard.press("Escape")
        transfer_dialog.wait_for(state="hidden")
        approval_task.get_by_role("button", name="通过", exact=True).click()
        approval_dialog = page.get_by_role("dialog")
        approval_dialog.locator("textarea").fill("浏览器端审批中心通过验收")
        approval_dialog.get_by_role("button", name="通过审批", exact=True).click()
        page.get_by_text("审批已通过").wait_for()
        approval_dialog.wait_for(state="hidden")

        page.get_by_role("link", name="期限管理", exact=True).click()
        page.get_by_role("button", name="新建期限", exact=True).click()
        deadline_dialog = page.get_by_role("dialog")
        deadline_title = f"浏览器验收期限-{int(time())}"
        deadline_dialog.locator("select").nth(0).select_option(label=f"{matter_number} · {matter_title}")
        deadline_dialog.locator("input").nth(0).fill(deadline_title)
        deadline_dialog.locator('input[type="datetime-local"]').fill("2027-12-31T18:00")
        page.get_by_role("button", name="保存期限", exact=True).click()
        page.get_by_text("期限已保存").wait_for()
        deadline_dialog.wait_for(state="hidden")
        page.get_by_text(deadline_title, exact=True).wait_for()

        page.get_by_role("link", name="案件中心", exact=True).click()
        matter_row = page.locator("tbody tr").filter(has_text=matter_title)
        matter_row.click()
        page.get_by_role("heading", name=matter_title, exact=True).wait_for()
        page.get_by_role("heading", name="案件概览", exact=True).wait_for()
        page.get_by_role("heading", name="办案团队", exact=True).wait_for()
        page.get_by_role("heading", name="关键期限", exact=True).wait_for()
        page.get_by_text(deadline_title, exact=True).wait_for()
        page.get_by_text(contract_title, exact=True).wait_for()
        page.get_by_role("button", name="编辑资料", exact=True).click()
        edit_matter_dialog = page.get_by_role("dialog")
        edit_matter_dialog.locator("textarea").fill("浏览器端案件资料编辑验收")
        edit_matter_dialog.get_by_role("button", name="保存案件", exact=True).click()
        page.locator(".el-message").filter(has_text="案件资料已更新").wait_for()
        edit_matter_dialog.wait_for(state="hidden")
        page.get_by_text("浏览器端案件资料编辑验收", exact=True).wait_for()
        page.get_by_role("button", name="确认立案", exact=True).click()
        lifecycle_dialog = page.get_by_role("dialog")
        lifecycle_dialog.locator("textarea").fill("冲突检索完成，浏览器验收确认立案")
        lifecycle_dialog.get_by_role("button", name="确认变更", exact=True).click()
        page.get_by_text("案件状态已更新").wait_for()
        page.get_by_text("ACTIVE", exact=True).first.wait_for()
        page.screenshot(path=RESULTS / "matter-workspace.png", full_page=True)

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
        page.get_by_role("dialog").locator("select").nth(0).select_option(
            "00000000-0000-0000-0002-000000000001"
        )
        page.get_by_placeholder("说明交付物与验收标准").fill("完成页面与接口联动验证")
        page.get_by_role("button", name="确认分派").click()
        page.get_by_text("协作任务已分派").wait_for()
        page.get_by_text(task_title).wait_for()
        for _ in range(40):
            notification_response = page.request.get(
                f"{BASE_URL}/api/notifications?page=1&size=30",
                headers={"X-Dev-User": "admin"},
            )
            if notification_response.ok and any(
                task_title in f"{item['title']} {item['content']}"
                for item in notification_response.json()["items"]
            ):
                break
            page.wait_for_timeout(500)
        else:
            raise AssertionError("协作任务通知未进入收件箱")
        page.get_by_role("button", name="通知", exact=True).click()
        notification_drawer = page.get_by_role("dialog", name="通知")
        notification_drawer.get_by_role("heading", name="通知中心", exact=True).wait_for()
        task_notification = notification_drawer.locator(
            "button.notification-item"
        ).filter(has_text=task_title)
        task_notification.wait_for()
        task_notification.click()
        notification_drawer.wait_for(state="hidden")
        page.get_by_role("heading", name="把口头交办，变成有负责人和期限的协作。").wait_for()

        page.get_by_role("link", name="电子卷宗", exact=True).click()
        page.get_by_role("button", name="新建卷宗").click()
        archive_number = f"UI-AJ-{int(time())}"
        page.get_by_test_id("archive-matter").select_option(
            label=f"{matter_number} · {matter_title}"
        )
        page.get_by_placeholder("如 AJ-2026-008").fill(archive_number)
        page.get_by_placeholder("案件或专项名称").fill("浏览器端验收卷宗")
        page.get_by_role("button", name="确认建卷").click()
        page.get_by_text("电子卷宗已建立").wait_for()
        page.get_by_text(archive_number).wait_for()

        page.get_by_role("link", name="文档中心", exact=True).click()
        page.locator("select").select_option(label=f"{matter_number} · {matter_title}")
        upload_input = page.locator('input[type="file"]')
        upload_input.wait_for(state="attached")
        assert upload_input.is_enabled(), "文档上传控件仍处于禁用状态"
        upload_path = RESULTS / "browser-upload.txt"
        upload_path.write_text("浏览器 CORS 与私有直传验收\n", encoding="utf-8")
        upload_input.set_input_files(upload_path)
        page.get_by_text("文件已安全入库并创建第 1 个版本").wait_for(timeout=20_000)
        page.get_by_text("browser-upload").first.wait_for()
        page.screenshot(path=RESULTS / "documents-after-upload.png", full_page=True)

        page.get_by_role("link", name="电子卷宗", exact=True).click()
        archive_card = page.locator("article.archive-card").filter(has_text=archive_number)
        archive_card.click()
        archive_dialog = page.get_by_role("dialog")
        archive_dialog.locator("select").select_option(label="browser-upload · CASE_FILE")
        archive_dialog.get_by_role("button", name="加入卷宗", exact=True).click()
        page.get_by_text("文档已加入卷宗").wait_for()
        archive_dialog.get_by_text("browser-upload", exact=True).wait_for()
        archive_dialog.get_by_text("001", exact=True).wait_for()
        page.keyboard.press("Escape")
        archive_dialog.wait_for(state="hidden")

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
    print("✓ 案件工作台聚合、资料编辑与生命周期流转")
    print("✓ 审批通过、驳回必填与转交候选人交互")
    print("✓ 上海办公室公告浏览器端定向发布")
    print("✓ 通知抽屉未读展示、已读与可信深链")
    print("✓ 协作任务浏览器端创建")
    print("✓ 电子卷宗浏览器端创建、案件关联与文件编目")
    print("✓ 文件浏览器直传（含 CORS）")
    print("✓ 钉钉登录回调缺码保护")
    print("✓ 登录页和业务页的桌面/移动端布局")
    print("✓ 控制台和页面运行时错误为 0")


if __name__ == "__main__":
    main()
