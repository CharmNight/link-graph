import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { RequestPromptDisclosure } from "../../../app/components/RequestPromptDisclosure";

describe("RequestPromptDisclosure", () => {
  it("does not render a viewable prompt control without preview text or artifact id", () => {
    render(
      <RequestPromptDisclosure
        promptPreview={null}
        promptPreviewArtifactId={null}
        promptPreviewAvailable
      />,
    );

    expect(screen.queryByRole("button", { name: /查看/ })).not.toBeInTheDocument();
  });

  it("requests artifact text when only an artifact id is available", async () => {
    const user = userEvent.setup();
    const onRequestArtifact = vi.fn();

    render(
      <RequestPromptDisclosure
        promptPreview={null}
        promptPreviewArtifactId="artifact:prompt"
        promptPreviewAvailable
        resolveArtifactText={() => null}
        onRequestArtifact={onRequestArtifact}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看提示词" }));

    expect(onRequestArtifact).toHaveBeenCalledWith("artifact:prompt");
    expect(screen.getByText("正在按需加载提示词...")).toBeInTheDocument();
  });

  it("renders full artifact text after it is resolved", async () => {
    const user = userEvent.setup();

    render(
      <RequestPromptDisclosure
        promptPreview={null}
        promptPreviewArtifactId="artifact:prompt"
        promptPreviewAvailable
        resolveArtifactText={() => "完整提示词内容"}
      />,
    );

    await user.click(screen.getByRole("button", { name: "查看提示词" }));

    expect(screen.getByText("完整提示词内容")).toBeInTheDocument();
  });
});
