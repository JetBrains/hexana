const path = require('path');

const runningInTeamCity = Boolean(process.env.TEAMCITY_VERSION || process.env.TEAMCITY_PROJECT_NAME);

module.exports = {
    timeout: 180000,
    slow: 30000,
    reporter: runningInTeamCity ? path.join(__dirname, 'out', 'teamcity-reporter.js') : 'spec',
};
