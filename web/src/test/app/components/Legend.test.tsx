import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Legend } from "../../../app/components/Legend";

describe("Legend", () => {
  it("shows UML relation badges for class diagrams", () => {
    render(<Legend analysisDisplayMode="CLASS_DIAGRAM" />);

    expect(screen.getByText("类图")).toBeInTheDocument();
    expect(screen.getByText("类")).toBeInTheDocument();
    expect(screen.getByText("接口")).toBeInTheDocument();
    expect(screen.getByText("抽象类")).toBeInTheDocument();
    expect(screen.getByText("继承")).toBeInTheDocument();
    expect(screen.getByText("实现")).toBeInTheDocument();
    expect(screen.getByText("字段关联")).toBeInTheDocument();
    expect(screen.getByText("类型依赖")).toBeInTheDocument();
  });
});
