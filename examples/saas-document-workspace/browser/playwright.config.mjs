// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'.',testMatch:'saas.spec.mjs',workers:1,fullyParallel:false,retries:0,
 timeout:45000,expect:{timeout:15000},reporter:[['./reporter.cjs']],
 outputDir:'../target/acceptance-private',
 use:{ignoreHTTPSErrors:true,trace:'off',screenshot:'off',video:'off',
  launchOptions:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}:{}}
});
