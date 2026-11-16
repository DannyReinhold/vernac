// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import config from './playwright.config.mjs';
export default {...config,testMatch:'upgrade.spec.mjs',timeout:90000,
 reporter:[['junit',{outputFile:'../target/upgrade-reports/TEST-UpgradeBaseline.xml'}]],
 outputDir:'../target/upgrade-private'};
