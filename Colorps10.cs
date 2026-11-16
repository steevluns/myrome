using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Colorps10
{
    public partial class MainForm : Form
    {
        [DllImport("gdi32.dll")]
        private static extern IntPtr GetDC(IntPtr hWnd);

        [DllImport("gdi32.dll")]
        private static extern bool SetDeviceGammaRamp(IntPtr hDC, ref Ramp lpRamp);

        [DllImport("user32.dll")]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll")]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct Ramp
        {
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 256)]
            public ushort[] Red;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 256)]
            public ushort[] Green;
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 256)]
            public ushort[] Blue;
        }

        private const int MOD_CTRL = 0x0002;
        private const int MOD_ALT = 0x0001;
        private const int HOTKEY_ID_1 = 1;
        private const int HOTKEY_ID_2 = 2;

        private TrackBar tbBrightness;
        private TrackBar tbContrast;
        private TrackBar tbGamma;

        private int currentBrightness = 128;
        private int currentContrast = 128;
        private int currentGamma = 128;

        private int p1B = 128, p1C = 128, p1G = 128;
        private int p2B = 128, p2C = 128, p2G = 128;

        public MainForm()
        {
            InitializeComponentCustom();
        }

        private void InitializeComponentCustom()
        {
            this.Text = "Colorps10";
            this.Size = new Size(400, 300);
            this.StartPosition = FormStartPosition.CenterScreen;

            tbBrightness = new TrackBar { Minimum = 0, Value = 128, Maximum = 255, Location = new Point(20, 20), Width = 340 };
            tbBrightness.Scroll += (s, e) => { currentBrightness = tbBrightness.Value; ApplySettings(); };

            tbContrast = new TrackBar { Minimum = 0, Value = 128, Maximum = 255, Location = new Point(20, 80), Width = 340 };
            tbContrast.Scroll += (s, e) => { currentContrast = tbContrast.Value; ApplySettings(); };

            tbGamma = new TrackBar { Minimum = 1, Value = 128, Maximum = 255, Location = new Point(20, 140), Width = 340 };
            tbGamma.Scroll += (s, e) => { currentGamma = tbGamma.Value; ApplySettings(); };

            this.Controls.Add(tbBrightness);
            this.Controls.Add(tbContrast);
            this.Controls.Add(tbGamma);

            this.Load += (s, e) =>
            {
                RegisterHotKey(this.Handle, HOTKEY_ID_1, MOD_CTRL | MOD_ALT, (uint)Keys.D1);
                RegisterHotKey(this.Handle, HOTKEY_ID_2, MOD_CTRL | MOD_ALT, (uint)Keys.D2);
            };

            this.FormClosing += (s, e) =>
            {
                UnregisterHotKey(this.Handle, HOTKEY_ID_1);
                UnregisterHotKey(this.Handle, HOTKEY_ID_2);
            };
        }

        private void ApplySettings()
        {
            IntPtr hDC = GetDC(IntPtr.Zero);
            Ramp ramp = new Ramp();
            ramp.Red = new ushort[256];
            ramp.Green = new ushort[256];
            ramp.Blue = new ushort[256];

            for (int i = 0; i < 256; i++)
            {
                int val = i;
                val = (val * currentBrightness) / 128;
                val = ((val - 128) * currentContrast / 128) + 128;
                val = (int)(Math.Pow(val / 255.0, currentGamma / 128.0) * 255.0);

                if (val > 65535) val = 65535;
                if (val < 0) val = 0;

                ushort v = (ushort)(val * 257);
                ramp.Red[i] = v;
                ramp.Green[i] = v;
                ramp.Blue[i] = v;
            }

            SetDeviceGammaRamp(hDC, ref ramp);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == 0x0312)
            {
                int id = m.WParam.ToInt32();
                if (id == HOTKEY_ID_1)
                {
                    p1B = currentBrightness; p1C = currentContrast; p1G = currentGamma;
                    MessageBox.Show("تم حفظ الملف الأول بنجاح");
                }
                else if (id == HOTKEY_ID_2)
                {
                    p2B = currentBrightness; p2C = currentContrast; p2G = currentGamma;
                    MessageBox.Show("تم حفظ الملف الثاني بنجاح");
                }
            }
            base.WndProc(ref m);
        }
    }
}