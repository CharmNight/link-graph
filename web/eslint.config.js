import js from "@eslint/js";
import tseslint from "typescript-eslint";
import reactPlugin from "eslint-plugin-react";
import reactHooks from "eslint-plugin-react-hooks";

export default tseslint.config(
    {
        ignores: ["dist/", "node_modules/", "coverage/"],
    },
    js.configs.recommended,
    ...tseslint.configs.recommended,
    {
        files: ["src/**/*.{ts,tsx}"],
        plugins: {
            react: reactPlugin,
            "react-hooks": reactHooks,
        },
        languageOptions: {
            parserOptions: {
                ecmaFeatures: { jsx: true },
            },
            globals: {
                window: "readonly",
                document: "readonly",
                console: "readonly",
                requestAnimationFrame: "readonly",
                cancelAnimationFrame: "readonly",
                ResizeObserver: "readonly",
                MutationObserver: "readonly",
                HTMLElement: "readonly",
                SVGElement: "readonly",
                DOMRect: "readonly",
                CustomEvent: "readonly",
                Event: "readonly",
                EventTarget: "readonly",
                Node: "readonly",
                Element: "readonly",
                getComputedStyle: "readonly",
                localStorage: "readonly",
                navigator: "readonly",
                history: "readonly",
                location: "readonly",
                performance: "readonly",
                URL: "readonly",
                URLSearchParams: "readonly",
                setTimeout: "readonly",
                clearTimeout: "readonly",
                setInterval: "readonly",
                clearInterval: "readonly",
                fetch: "readonly",
                AbortController: "readonly",
            },
        },
        rules: {
            ...reactPlugin.configs.recommended.rules,
            ...reactHooks.configs.recommended.rules,
            "react/react-in-jsx-scope": "off",
            "react/prop-types": "off",
            "react/jsx-key": "error",
            "react-hooks/exhaustive-deps": "error",
            "@typescript-eslint/no-unused-vars": [
                "error",
                {
                    argsIgnorePattern: "^_",
                    varsIgnorePattern: "^_",
                    caughtErrorsIgnorePattern: "^_",
                },
            ],
            "@typescript-eslint/no-explicit-any": "off",
        },
        settings: {
            react: { version: "detect" },
        },
    },
);
