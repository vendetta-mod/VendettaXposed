const oldObjectCreate = this.Object.create;
const win = this;
win.Object.create = (...args) => {
    const obj = oldObjectCreate.apply(win.Object, args);
    if (args[0] === null) {
        win.modules = obj;
        win.Object.create = oldObjectCreate;
    }
    return obj;
};