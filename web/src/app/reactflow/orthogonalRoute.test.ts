import { describe, expect, it } from "vitest";
import {
  reanchorRouteEnd,
  reanchorRouteStart,
  reanchorRouteToEndpoints,
} from "./orthogonalRoute";

describe("orthogonalRoute", () => {
  it("keeps the original end point when reanchoring the start of a straight route", () => {
    const route = {
      sections: [
        {
          startPoint: { x: 626, y: 252 },
          endPoint: { x: 626, y: 364 },
        },
      ],
    };

    const adjusted = reanchorRouteStart(route, { x: 225.64, y: 234.57 }, "bottom");

    expect(adjusted?.sections[0]).toEqual({
      startPoint: { x: 225.64, y: 234.57 },
      endPoint: { x: 626, y: 364 },
      bendPoints: undefined,
    });
  });

  it("keeps the original start point when reanchoring the end of a straight route", () => {
    const route = {
      sections: [
        {
          startPoint: { x: 626, y: 252 },
          endPoint: { x: 626, y: 364 },
        },
      ],
    };

    const adjusted = reanchorRouteEnd(route, { x: 626, y: 365 }, "top");

    expect(adjusted?.sections[0]).toEqual({
      startPoint: { x: 626, y: 252 },
      endPoint: { x: 626, y: 365 },
      bendPoints: undefined,
    });
  });

  it("preserves both live endpoints when reanchoring a straight route at both ends", () => {
    const route = {
      sections: [
        {
          startPoint: { x: 626, y: 252 },
          endPoint: { x: 626, y: 364 },
        },
      ],
    };

    const adjusted = reanchorRouteToEndpoints(
      route,
      { x: 225.64, y: 234.57 },
      "bottom",
      { x: 626, y: 365 },
      "top",
    );

    expect(adjusted?.sections[0]).toEqual({
      startPoint: { x: 225.64, y: 234.57 },
      endPoint: { x: 626, y: 365 },
      bendPoints: undefined,
    });
  });
});
