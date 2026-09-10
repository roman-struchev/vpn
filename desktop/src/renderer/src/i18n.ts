export type Lang = 'ru' | 'en';

const translations = {
  ru: {
    appName: 'NextGen VPN',
    loginTitle: 'Вход',
    registerTitle: 'Зарегистрироваться',
    email: 'Email',
    password: 'Пароль',
    login: 'Войти',
    register: 'Зарегистрироваться',
    toggleToRegister: 'Нет аккаунта? Создать',
    toggleToLogin: 'Уже есть аккаунт? Войти',
    fieldRequired: 'Заполните это поле',

    stateDisconnected: 'Отключено',
    stateConnecting: 'Подключение…',
    stateConnected: 'Защищено',
    stateReconnecting: 'Переподключение…',
    stateOperatorBlocked: 'Ограничение вашего оператора связи',
    stateError: 'Ошибка подключения',
    noSubscription: 'Нет активной подписки',
    expiresAt: 'Действует до',
    connect: 'Подключить',
    disconnect: 'Отключить',
    nodeRegion: 'Регион ноды',
    operatorBlockedTitle: 'Это ограничение оператора, а не сервиса',
    operatorBlockedBody:
      'Российские сайты открываются, а внешние — нет. Это делает ваш оператор связи. Мы автоматически подбираем резервные ноды, попробуйте переподключиться через минуту.',

    navConnect: 'Подключение',
    navDevices: 'Устройства',
    navProfile: 'Профиль',

    devicesTitle: 'Устройства',
    thisDeviceAutoAdded: 'Это приложение само появится в списке при первом успешном подключении — добавлять его вручную не нужно.',
    addAnotherDevice: 'Добавить другое устройство',
    deviceName: 'Название устройства',
    revoke: 'Отозвать',

    balance: 'Баланс',
    referralLink: 'Реферальный код',
    logout: 'Выйти',
  },
  en: {
    appName: 'NextGen VPN',
    loginTitle: 'Sign in',
    registerTitle: 'Create account',
    email: 'Email',
    password: 'Password',
    login: 'Sign in',
    register: 'Create account',
    toggleToRegister: 'No account? Create one',
    toggleToLogin: 'Already have an account? Sign in',
    fieldRequired: 'This field is required',

    stateDisconnected: 'Disconnected',
    stateConnecting: 'Connecting…',
    stateConnected: 'Protected',
    stateReconnecting: 'Reconnecting…',
    stateOperatorBlocked: "Your carrier is restricting this",
    stateError: 'Connection error',
    noSubscription: 'No active subscription',
    expiresAt: 'Expires',
    connect: 'Connect',
    disconnect: 'Disconnect',
    nodeRegion: 'Node region',
    operatorBlockedTitle: "This is your carrier's restriction, not ours",
    operatorBlockedBody:
      "Russian sites load fine, but foreign ones don't — that's your carrier blocking traffic. We're switching to backup nodes automatically; try reconnecting in a minute.",

    navConnect: 'Connect',
    navDevices: 'Devices',
    navProfile: 'Profile',

    devicesTitle: 'Devices',
    thisDeviceAutoAdded: 'This app registers itself the first time it connects successfully — no need to add it manually.',
    addAnotherDevice: 'Add another device',
    deviceName: 'Device name',
    revoke: 'Revoke',

    balance: 'Balance',
    referralLink: 'Referral code',
    logout: 'Sign out',
  },
} satisfies Record<Lang, Record<string, string>>;

const lang: Lang = navigator.language.toLowerCase().startsWith('ru') ? 'ru' : 'en';

export const t = translations[lang];
