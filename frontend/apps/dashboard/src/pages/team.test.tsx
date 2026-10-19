import type { TeamInvite, TeamMember } from "@cutpay/api-client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TeamPage } from "./TeamPage";

let role = "owner";
vi.mock("../api", () => ({
  api: {
    listTeamMembers: vi.fn(),
    listTeamInvites: vi.fn(),
    inviteTeamMember: vi.fn(),
    changeTeamMemberRole: vi.fn(),
    removeTeamMember: vi.fn(),
    revokeTeamInvite: vi.fn(),
  },
}));
vi.mock("../auth/AuthProvider", () => ({
  useSession: () => ({ user: { id: "usr_me", email: "me@example.com", full_name: "Me" }, role }),
}));

const { api } = await import("../api");

const member = (id: string, name: string, memberRole: TeamMember["role"]): TeamMember => ({
  object: "team_member",
  user_id: id,
  email: `${id}@example.com`,
  full_name: name,
  role: memberRole,
  joined_at: "2026-10-01T10:00:00Z",
  last_login_at: null,
});

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={["/settings/team"]}>
        <TeamPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("TeamPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    role = "owner";
    vi.mocked(api.listTeamMembers).mockResolvedValue({
      object: "list",
      data: [member("usr_me", "Me", "owner"), member("usr_a", "Ada Admin", "admin"), member("usr_v", "Vera Viewer", "viewer")],
    });
    vi.mocked(api.listTeamInvites).mockResolvedValue({ object: "list", data: [] });
  });

  it("lets an owner change and remove others but not themselves", async () => {
    renderPage();
    expect(await screen.findByLabelText("Role of Vera Viewer")).toBeInTheDocument();
    expect(screen.getByLabelText("Role of Ada Admin")).toBeInTheDocument();
    expect(screen.queryByLabelText("Role of Me")).not.toBeInTheDocument();
    expect(within(screen.getByTestId("member-usr_me@example.com")).getByText("Owner")).toBeInTheDocument();
  });

  it("does not let an admin touch another admin or offer the admin role", async () => {
    role = "admin";
    renderPage();
    await screen.findByLabelText("Role of Vera Viewer");
    expect(screen.queryByLabelText("Role of Ada Admin")).not.toBeInTheDocument();
    const options = within(screen.getByLabelText("Role of Vera Viewer")).getAllByRole("option").map((o) => o.textContent);
    expect(options).not.toContain("Admin");
  });

  it("changes a role", async () => {
    vi.mocked(api.changeTeamMemberRole).mockResolvedValue(member("usr_v", "Vera Viewer", "support"));
    renderPage();
    await userEvent.selectOptions(await screen.findByLabelText("Role of Vera Viewer"), "support");
    expect(api.changeTeamMemberRole).toHaveBeenCalledWith("usr_v", "support");
  });

  it("invites someone and shows the link once", async () => {
    const created: TeamInvite = {
      object: "invitation",
      id: "inv_1",
      email: "new@example.com",
      role: "developer",
      invited_by: "me@example.com",
      created_at: "2026-10-19T10:00:00Z",
      expires_at: "2026-10-26T10:00:00Z",
      status: "pending",
      accept_path: "/accept-invite?token=inv_secret",
    };
    vi.mocked(api.inviteTeamMember).mockResolvedValue(created);
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Invite someone" }));
    const form = screen.getByRole("dialog", { name: "Invite someone" });
    await userEvent.type(within(form).getByLabelText("Email"), "new@example.com");
    await userEvent.click(within(form).getByRole("button", { name: "Send invitation" }));

    expect(api.inviteTeamMember).toHaveBeenCalledWith("new@example.com", "developer");
    const link = await screen.findByLabelText("Invitation link");
    expect(link).toHaveValue(`${window.location.origin}/accept-invite?token=inv_secret`);
  });

  it("rejects a malformed address before asking the server", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Invite someone" }));
    const form = screen.getByRole("dialog", { name: "Invite someone" });
    await userEvent.type(within(form).getByLabelText("Email"), "nobody");
    await userEvent.click(within(form).getByRole("button", { name: "Send invitation" }));
    expect(await within(form).findByText("Enter a valid email")).toBeInTheDocument();
    expect(api.inviteTeamMember).not.toHaveBeenCalled();
  });

  it("shows only a notice to roles outside the team", async () => {
    role = "viewer";
    renderPage();
    expect(await screen.findByText("Only owners and admins can see the team.")).toBeInTheDocument();
    expect(api.listTeamMembers).not.toHaveBeenCalled();
  });
});
